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

#![allow(non_snake_case)]

use crate::{
    hook_netpatch,
    hook_netpatch::OverrideType,
    rt_init,
    rt_init::{MppatchCtx, MppatchFeature},
    rt_patch::PatchedFunction,
};
use anyhow::Result;
use dlopen::raw::Library;
use libc::{c_char, c_int};
use log::debug;
use mlua::{
    ffi::{
        luaL_checkstring, lua_getfenv, lua_gettable, lua_gettop, lua_insert, lua_isnil, lua_pop,
        lua_pushcfunction, lua_pushstring, lua_settable, lua_type, LUA_REGISTRYINDEX, LUA_TSTRING,
    },
    lua_State,
    prelude::{LuaFunction, LuaString},
    Lua, Table,
};
use std::{ffi::CStr, ffi::c_void, sync::Mutex};

/// Pure Rust strcmp — compares a C string against a byte array (expected must include
/// its trailing \0 in the slice). No CRT dependency, no allocation, no strlen.
unsafe fn c_str_eq(ptr: *const libc::c_char, expected: &[u8]) -> bool {
    // Read first byte first — this is the most likely crash point if ptr is bad.
    // We check it before the loop so we can report what we got.
    let first = *ptr as u8;
    for (i, &exp) in expected.iter().enumerate() {
        let c = if i == 0 { first } else { *ptr.add(i) as u8 };
        if c != exp {
            return false;
        }
        if c == 0 {
            return true;
        }
    }
    false
}

/// Trace helper — appends to a file that survives abort().
/// Each call adds a new line so we see the full execution sequence.
/// Relative path only — inside #[ctor] (DllMain), current_exe() panics silently.
fn trace(msg: &str) {
    use std::io::Write;
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("mppatch_trace.txt")
        .and_then(|mut f| writeln!(f, "{msg}"));
}

type FnType = unsafe extern "C-unwind" fn(*mut lua_State) -> c_int;

/// Direct patch on the in-memory code of lGetMemoryUsage in the original DLL.
/// Catches ALL callers including the Lua VM (which calls via C function pointer,
/// bypassing the DLL export table that proxy hooks rely on).
#[cfg(windows)]
static GET_MEMORY_USAGE_DIRECT: Mutex<Option<PatchedFunction>> = Mutex::new(None);

/// Direct patch for lCollectMemoryUsage — same rationale.
#[cfg(windows)]
static COLLECT_MEMORY_USAGE_DIRECT: Mutex<Option<PatchedFunction>> = Mutex::new(None);

/// Direct patch on the in-memory code of PushDatabase in the original DLL.
/// Catches ALL callers including internal C++ code that caches function pointers.
#[cfg(windows)]
static PUSH_DATABASE_DIRECT: Mutex<Option<PatchedFunction>> = Mutex::new(None);

type PushDbFn = unsafe extern "C-unwind" fn(*mut lua_State, *mut c_void);

/// Direct patch on the in-memory code of PushDatabase in the original DLL.
/// After the original creates the DB table with methods (GetMemoryUsage, Execute,
/// Query, etc.), we replace GetMemoryUsage with our sentinel-intercepting proxy.
#[cfg(windows)]
unsafe extern "C-unwind" fn pushDatabaseProxy(
    lua_c: *mut lua_State,
    conn: *mut c_void,
) {
    trace("PDB01: PushDatabase proxy entered");

    let guard = PUSH_DATABASE_DIRECT.lock().unwrap();
    let patch = guard.as_ref().expect("PushDatabase direct patch not initialized");
    let orig: PushDbFn = std::mem::transmute(patch.old_function());
    orig(lua_c, conn);

    // After PushDatabase returns, the DB table is at the top of the stack.
    // Replace GetMemoryUsage with our sentinel-intercepting proxy.
    trace("PDB02: original returned, replacing GetMemoryUsage on DB table");
    lua_pushstring(lua_c, b"GetMemoryUsage\0".as_ptr() as *const c_char);
    lua_pushcfunction(lua_c, lGetMemoryUsageProxy);
    lua_settable(lua_c, -3);
    trace("PDB03: GetMemoryUsage replaced on DB table");
}

unsafe fn lGetMemoryUsage(lua: *mut lua_State) -> c_int {
    trace("L10: lGetMemoryUsage entered");
    let guard = GET_MEMORY_USAGE_DIRECT.lock().unwrap();
    let patch = guard.as_ref().expect("lGetMemoryUsage direct patch not initialized");
    let orig: FnType = std::mem::transmute(patch.old_function());
    trace("L13: calling original lGetMemoryUsage");
    let r = orig(lua);
    trace(&format!("L14: original returned {r}"));
    r
}

pub fn init(ctx: &MppatchCtx) -> Result<()> {
    trace("L50: hook_lua::init started");
    unsafe {
        #[cfg(windows)]
        {
            // Prepend exe_dir to get the full path to the original DLL.
            // The proxy module has already loaded it; we open it again (refcount++)
            // so we can resolve the mangled C++ symbol to its in-memory address.
            let mut lib_path = ctx.exe_dir().to_path_buf();
            lib_path.push("CvGameDatabase_Original.dll");
            let orig_lib = Library::open(&lib_path)?;

            // ── lGetMemoryUsage (direct) ──────────────────────────────────
            log::info!("Direct-patching lGetMemoryUsage...");
            let addr_lget: *mut c_void = orig_lib.symbol(
                "?lGetMemoryUsage@Lua@Scripting@Database@@SAHPAUlua_State@@@Z",
            )?;
            log::info!("lGetMemoryUsage at {:p}", addr_lget);
            let patch = PatchedFunction::create(
                addr_lget,
                lGetMemoryUsageProxy as *const c_void,
                7,
                "lGetMemoryUsage (direct)",
            );
            *GET_MEMORY_USAGE_DIRECT.lock().unwrap() = Some(patch);

            // ── lCollectMemoryUsage (direct) ─────────────────────────────
            log::info!("Direct-patching lCollectMemoryUsage...");
            let addr_lcollect: *mut c_void = orig_lib.symbol(
                "?lCollectMemoryUsage@Lua@Scripting@Database@@SAHPAUlua_State@@@Z",
            )?;
            log::info!("lCollectMemoryUsage at {:p}", addr_lcollect);
            let patch2 = PatchedFunction::create(
                addr_lcollect,
                lCollectMemoryUsageProxy as *const c_void,
                7,
                "lCollectMemoryUsage (direct)",
            );
            *COLLECT_MEMORY_USAGE_DIRECT.lock().unwrap() = Some(patch2);

            // ── PushDatabase (direct) ──────────────────────────────────────
            log::info!("Direct-patching PushDatabase...");
            let addr_pdb: *mut c_void = orig_lib.symbol(
                "?PushDatabase@Lua@Scripting@Database@@SAXPAUlua_State@@AAVConnection@3@@Z",
            )?;
            log::info!("PushDatabase at {:p}", addr_pdb);
            let patch3 = PatchedFunction::create(
                addr_pdb,
                pushDatabaseProxy as *const c_void,
                7,
                "PushDatabase (direct)",
            );
            *PUSH_DATABASE_DIRECT.lock().unwrap() = Some(patch3);
        }

        trace("L56: PushDatabase direct patch applied");
    }
    trace("L59: hook_lua::init completed");
    Ok(())
}

#[ctor::dtor]
fn destroy_usage() {
    #[cfg(windows)]
    {
        // Safe cleanup during DLL_PROCESS_DETACH:
        // - Avoid .unwrap() — a poisoned mutex means something panicked during
        //   gameplay, and panicking here aborts the detach handler, zombifying
        //   the process.
        // - Wrap in catch_unwind so a fault in VirtualProtect/ptr::copy during
        //   detach doesn't prevent process exit.
        let _ = std::panic::catch_unwind(|| {
            if let Ok(mut guard) = GET_MEMORY_USAGE_DIRECT.lock() {
                *guard = None;
            }
            if let Ok(mut guard) = COLLECT_MEMORY_USAGE_DIRECT.lock() {
                *guard = None;
            }
            if let Ok(mut guard) = PUSH_DATABASE_DIRECT.lock() {
                *guard = None;
            }
        });
    }
}

const LUA_SENTINEL_C: &[u8; 37] = b"216f0090-85dd-4061-8371-3d8ba2099a70\0";

const LUA_TABLE_INDEX: &str = "4f9ef697-7746-45d3-9c2d-f2121464a359";
const LUA_TABLE_INDEX_C: &CStr =
    match CStr::from_bytes_with_nul("4f9ef697-7746-45d3-9c2d-f2121464a359\0".as_bytes()) {
        Ok(x) => x,
        Err(_) => panic!("???"),
    };

const LUA_FUNC_GET_GLOBALS: &str = "7fe157f7-909f-4cbc-9257-8156d1d84a29";
const LUA_FUNC_GET_GLOBALS_C: &CStr =
    match CStr::from_bytes_with_nul("7fe157f7-909f-4cbc-9257-8156d1d84a29\0".as_bytes()) {
        Ok(x) => x,
        Err(_) => panic!("???"),
    };

fn create_mppatch_table(lua_c: *mut lua_State, lua: &Lua) -> Result<()> {
    trace("L20: create_mppatch_table started");
    trace("L21: building MPPatch function table...");

    let ctx = rt_init::get_ctx();

    let patch_table = lua.create_table()?;
    patch_table.set("__mppatch_marker", 1)?;

    // misc functions
    patch_table.set(
        "debugPrint",
        lua.create_function(|_, value: LuaString| {
            debug!(target: "<lua>", "{}", value.to_string_lossy());
            Ok(())
        })?,
    )?;
    patch_table.set("getGlobals", lua.create_function(|lua, _: ()| Ok(lua.globals()))?)?;

    // shared table
    patch_table.set("shared", lua.create_table()?)?;

    // version table
    {
        let version_table = lua.create_table()?;
        version_table.set("versionString", ctx.version())?;
        version_table.set("platform", ctx.version_info.platform.name())?;
        version_table.set("sha256", ctx.sha256())?;
        version_table.set("buildId", ctx.build_id())?;
        version_table.set("valid", true)?;
        patch_table.set("version", version_table)?;
    }

    // config table
    {
        let config_table = lua.create_table()?;
        config_table.set("enableLogging", ctx.has_feature(MppatchFeature::Logging))?;
        config_table.set("enableDebug", ctx.has_feature(MppatchFeature::Debug))?;
        config_table.set("enableMultiplayerPatch", ctx.has_feature(MppatchFeature::Multiplayer))?;
        config_table.set("enableLuaJIT", ctx.has_feature(MppatchFeature::LuaJit))?;
        patch_table.set("config", config_table)?;
    }

    trace("L22: getting globals table via sentinel method");

    // find actual globals table
    let globals = {
        unsafe {
            lua_pushstring(lua_c, LUA_FUNC_GET_GLOBALS_C.as_ptr());
            lua_pushcfunction(lua_c, get_globals_table);
            lua_settable(lua_c, LUA_REGISTRYINDEX);
        }

        let get_globals: LuaFunction = lua.named_registry_value(LUA_FUNC_GET_GLOBALS)?;
        let table: Table = get_globals.call(())?;
        table
    };

    // globals table
    {
        trace("L23: building globals sub-table");
        let globals_table = lua.create_table()?;
        globals_table.set("rawget", globals.get::<_, LuaFunction>("rawget")?)?;
        globals_table.set("rawset", globals.get::<_, LuaFunction>("rawset")?)?;
        patch_table.set("globals", globals_table)?;
    }

    // NetPatch table
    {
        trace("L24: building NetPatch sub-table");
        let net_patch_table = lua.create_table()?;

        net_patch_table.set(
            "pushMod",
            lua.create_function(|_, (name, ver): (String, i32)| {
                hook_netpatch::add_mod(&name, ver);
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "pushDLC",
            lua.create_function(|_, guid: String| {
                hook_netpatch::add_dlc(&guid);
                Ok(())
            })?,
        )?;

        net_patch_table.set(
            "overrideReloadMods",
            lua.create_function(|_, _: ()| {
                hook_netpatch::add_override(OverrideType::ForceReloadMods);
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "overrideReloadDLC",
            lua.create_function(|_, _: ()| {
                hook_netpatch::add_override(OverrideType::ForceReloadDlcs);
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "overrideModList",
            lua.create_function(|_, _: ()| {
                hook_netpatch::add_override(OverrideType::OverrideMods);
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "overrideDLCList",
            lua.create_function(|_, _: ()| {
                hook_netpatch::add_override(OverrideType::OverrideDlcs);
                Ok(())
            })?,
        )?;

        net_patch_table.set(
            "install",
            lua.create_function(|_, _: ()| {
                hook_netpatch::install();
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "reset",
            lua.create_function(|_, _: ()| {
                hook_netpatch::reset();
                Ok(())
            })?,
        )?;
        net_patch_table.set(
            "isOverridePending",
            lua.create_function(|_, _: ()| Ok(hook_netpatch::is_override_pending()))?,
        )?;
        net_patch_table.set(
            "setOverridePending",
            lua.create_function(|_, val: bool| {
                hook_netpatch::set_override_pending(val);
                Ok(())
            })?,
        )?;

        patch_table.set("NetPatch", net_patch_table)?;
    }

    trace("L29: storing patch table in Lua registry");
    lua.set_named_registry_value(LUA_TABLE_INDEX, patch_table)?;
    trace("L30: create_mppatch_table completed successfully");
    Ok(())
}

/// this can't be done entire in mlua, unfortunately
unsafe extern "C-unwind" fn get_globals_table(lua_c: *mut lua_State) -> c_int {
    trace("L40: get_globals_table entered");
    lua_pushstring(lua_c, CStr::from_bytes_with_nul(b"\0").unwrap().as_ptr()); // S
    lua_pushstring(lua_c, CStr::from_bytes_with_nul(b"gsub\0").unwrap().as_ptr()); // S S
    trace("L41: calling lua_gettable");
    lua_gettable(lua_c, -2);
    trace("L42: calling lua_getfenv");
    lua_getfenv(lua_c, -1);
    trace("L43: calling lua_insert/pop");
    lua_insert(lua_c, -3);
    lua_pop(lua_c, 2);
    trace("L44: get_globals_table returning");
    1
}

/// Separate trace file — only writes when we get a non-0x4 pointer (potential sentinel).
/// This survives even if the main trace file write starts failing silently.
fn trace_sentinel(msg: &str) {
    use std::io::Write;
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("mppatch_sentinel_detect.txt")
        .and_then(|mut f| writeln!(f, "{msg}"));
}

/// Extracted sentinel check and table creation — shared by lGetMemoryUsageProxy
/// and lCollectMemoryUsageProxy. Returns Some(n) if sentinel was handled (return n
/// from the proxy), or None if we should delegate to the original function.
unsafe fn handle_sentinel(lua_c: *mut lua_State) -> Option<c_int> {
    let top = lua_gettop(lua_c);
    let ty = lua_type(lua_c, 1);
    trace(&format!("L01b: lua_gettop={top}, lua_type(1)={ty}"));

    // Guard: during Lua VM bootstrapping, the direct patch on lGetMemoryUsage
    // catches calls before the lua_State is initialized. lua_gettop returns an
    // absurd value (e.g. 255) because the struct fields are uninitialized.
    // Calling the original function would crash. Return 0 (no values pushed)
    // as a safe no-op — this is harmless since no Lua code is running yet.
    if top > 100 {
        trace("L01y: Lua state not initialized (top={top}), returning 0");
        return Some(0);
    }

    if ty != LUA_TSTRING {
        trace("L01x: arg1 is not string, delegating to original");
        return None;
    }

    trace("L01c: arg1 is string, checking sentinel");
    let raw = luaL_checkstring(lua_c, 1);
    trace("L01d: luaL_checkstring returned");
    trace(&format!("L01d2: raw ptr={:p}", raw));
    // During early engine init, luaL_checkstring can return a garbage pointer
    // (e.g. 0x4) from a corrupted/not-yet-initialized Lua state. Guard against
    // dereferencing invalid pointers — everything below 64KB is unmapped on
    // x86 Windows, so this is a safe canary.
    if (raw as usize) < 0x10000 {
        trace("L01e: skipping sentinel check (invalid ptr), returning 0");
        return Some(0);
    }
    // Log non-0x4 pointer to separate trace file — if the sentinel call reaches
    // us with a valid string, it will appear here even if main trace file fails.
    trace_sentinel(&format!("VALID PTR: raw={:p}, top={top}", raw));
    trace("L01d1: comparing with c_str_eq");
    if c_str_eq(raw, LUA_SENTINEL_C) {
        trace("L02: sentinel matched, building MPPatch table");
        trace("L02a: pushing table index to registry");
        lua_pushstring(lua_c, LUA_TABLE_INDEX_C.as_ptr());
        trace("L02b: calling lua_gettable");
        lua_gettable(lua_c, LUA_REGISTRYINDEX);
        trace("L02c: checking if table exists");
        if lua_isnil(lua_c, lua_gettop(lua_c)) != 0 {
            trace("L03: no existing table, creating new one");
            lua_pop(lua_c, 1);

            trace("L03a: initializing Lua from ptr");
            let lua = Lua::init_from_ptr(lua_c);
            trace("L03b: creating MPPatch table");
            rt_init::check_error(create_mppatch_table(lua_c, &lua));
            trace("L03c: dropping Lua handle");
            drop(lua);
            trace("L03d: table creation done");
        } else {
            trace("L04: existing table found in registry");
            lua_pop(lua_c, 1);
        }

        trace("L05: pushing MPPatch table to Lua stack");
        lua_pushstring(lua_c, LUA_TABLE_INDEX_C.as_ptr());
        lua_gettable(lua_c, LUA_REGISTRYINDEX);
        trace("L05a: returning 1");
        Some(1)
    } else {
        // Valid pointer but NOT our sentinel — log what string we got (first 64 chars).
        let actual = std::ffi::CStr::from_ptr(raw).to_string_lossy();
        trace(&format!("L06: not sentinel, got \"{actual:.64}\", delegating"));
        None
    }
}

pub unsafe extern "C-unwind" fn lGetMemoryUsageProxy(lua_c: *mut lua_State) -> c_int {
    trace("L01: lGetMemoryUsageProxy entered");

    crate::ensure_initialized();
    trace("L01a: ensure_initialized done");

    if let Some(result) = handle_sentinel(lua_c) {
        return result;
    }

    lGetMemoryUsage(lua_c)
}

pub unsafe extern "C-unwind" fn lCollectMemoryUsageProxy(lua_c: *mut lua_State) -> c_int {
    trace("LC01: lCollectMemoryUsageProxy entered");

    crate::ensure_initialized();
    trace("LC01a: ensure_initialized done");

    if let Some(result) = handle_sentinel(lua_c) {
        return result;
    }

    lCollectMemoryUsage(lua_c)
}

/// Calls the original lCollectMemoryUsage via the direct-patch trampoline.
unsafe fn lCollectMemoryUsage(lua: *mut lua_State) -> c_int {
    trace("LC10: lCollectMemoryUsage entered");
    let guard = COLLECT_MEMORY_USAGE_DIRECT.lock().unwrap();
    let patch = guard.as_ref().expect("lCollectMemoryUsage direct patch not initialized");
    let orig: FnType = std::mem::transmute(patch.old_function());
    trace("LC13: calling original lCollectMemoryUsage");
    let r = orig(lua);
    trace(&format!("LC14: original returned {r}"));
    r
}
