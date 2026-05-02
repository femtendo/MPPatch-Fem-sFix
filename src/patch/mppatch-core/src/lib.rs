/*
 * Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

use crate::rt_init::MppatchFeature;
use ctor::ctor;
use std::sync::atomic::{AtomicBool, Ordering};

#[cfg(windows)]
mod hook_lua;
#[cfg(unix)]
mod hook_luajit;
mod hook_netpatch;
#[cfg(windows)]
mod hook_proxy;
mod rt_cpplist;
mod rt_init;
mod rt_linking;
mod rt_patch;
mod rt_platform;
mod versions;

/// Phase A: runs inside DllMain (via #[ctor]).
/// All init is done here because Civ5 immediately calls database proxy functions
/// after loading the DLL, and the proxy stubs must be patched before first use.
/// LoadLibrary("CvGameDatabase_Original.dll") is technically warned against inside
/// DllMain, but the original DLL's dependencies (KERNEL32, MSVCR90, lua51_Win32)
/// are all already loaded, so no deadlock risk in practice.
fn trace(msg: &str) {
    // Overwrite — only keep the latest trace step.
    // Relative path only — inside #[ctor] (DllMain), current_exe() panics silently
    // because std internals aren't initialized yet and panic=abort kills the process.
    let _ = std::fs::write("mppatch_trace.txt", msg);
}

fn ctor_impl() -> anyhow::Result<()> {
    trace("C01: ctor_impl started");
    let ctx = rt_init::run()?;
    trace("C02: rt_init::run completed");
    rt_linking::init(ctx)?;
    trace("C03: rt_linking::init completed");
    #[cfg(windows)]
    if ctx.has_feature(MppatchFeature::Multiplayer) {
        trace("C04: starting hook_proxy::init");
        // Load CvGameDatabase_Original.dll and patch proxy stubs with JMPs.
        // This MUST run before hook_lua and hook_netpatch because they resolve
        // DllProxy symbols through the loaded library.
        hook_proxy::init(ctx)?;
        trace("C05: hook_proxy::init completed");
        trace("C06: starting hook_lua::init");
        hook_lua::init(&ctx)?;
        trace("C07: hook_lua::init completed");
        hook_netpatch::init(&ctx)?;
        trace("C08: hook_netpatch::init completed");
    }
    #[cfg(unix)]
    if ctx.has_feature(MppatchFeature::LuaJit) {
        hook_luajit::init(&ctx)?;
    }
    trace("C09: ctor_impl completed successfully");
    Ok(())
}

/// Phase B: runs outside DllMain (called lazily from lGetMemoryUsageProxy).
/// Only handles Lua runtime table injection — no LoadLibrary or proxy patching.
static DEFERRED_INIT_DONE: AtomicBool = AtomicBool::new(false);
pub fn ensure_initialized() {
    DEFERRED_INIT_DONE.store(true, Ordering::SeqCst);
}

#[ctor]
fn ctor() {
    // Relative path only — see trace() above for why current_exe() is unsafe here.
    let _ = std::fs::write("mppatch_ctor.txt", "ctor started\n");
    rt_init::check_error(ctor_impl());
}
