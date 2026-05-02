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
    rt_cpplist::{CppList, CppListRaw},
    rt_init,
    rt_init::MppatchCtx,
    rt_linking::PatcherContext,
};
use anyhow::Result;
use enumset::*;
use libc::c_int;
use log::debug;
use std::{
    ffi::{c_void, CStr},
    fmt::{Debug, Formatter},
    mem,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
};

#[cfg(all(windows, target_env = "msvc"))]
type FnType = unsafe extern "thiscall-unwind" fn(
    *mut c_void,
    *mut CppListRaw<Guid>,
    *mut CppListRaw<ModInfo>,
    bool,
    bool,
) -> c_int;

#[cfg(all(windows, target_env = "gnu"))]
type FnType = unsafe extern "thiscall" fn(
    *mut c_void,
    *mut CppListRaw<Guid>,
    *mut CppListRaw<ModInfo>,
    bool,
    bool,
) -> c_int;

#[cfg(unix)]
type FnType = unsafe extern "C-unwind" fn(
    *mut c_void,
    *mut CppListRaw<Guid>,
    *mut CppListRaw<ModInfo>,
    bool,
    bool,
) -> c_int;

static SET_ACTIVE_DLC_AND_MODS: Mutex<PatcherContext<FnType>> = Mutex::new(PatcherContext::new());

pub unsafe fn SetActiveDLCAndMods(
    this: *mut c_void,
    dlc_list: *mut CppListRaw<Guid>,
    mod_list: *mut CppListRaw<ModInfo>,
    reload_dlc: bool,
    reload_mods: bool,
) -> c_int {
    let ctx = rt_init::get_ctx();
    let func = SET_ACTIVE_DLC_AND_MODS
        .lock()
        .unwrap()
        .as_func_fallback(ctx.version_info.sym_SetActiveDLCAndMods);
    func(this, dlc_list, mod_list, reload_dlc, reload_mods)
}

fn hook_install() {
    let ctx = rt_init::get_ctx();
    log::info!("Applying SetActiveDLCAndMods patch...");
    unsafe {
        rt_init::check_error(
            SET_ACTIVE_DLC_AND_MODS
                .lock()
                .unwrap()
                .patch(ctx.version_info.sym_SetActiveDLCAndMods, SetActiveDLCAndModsProxy),
        );
    }
}
fn hook_unpatch() {
    SET_ACTIVE_DLC_AND_MODS.lock().unwrap().unpatch();
}

pub fn init(_: &MppatchCtx) -> Result<()> {
    #[cfg(unix)]
    hook_install();
    Ok(())
}

#[ctor::dtor]
fn destroy_set_dlc() {
    let _ = std::panic::catch_unwind(|| {
        if let Ok(mut guard) = SET_ACTIVE_DLC_AND_MODS.lock() {
            guard.unpatch();
        }
    });
}

#[derive(Debug)]
struct NetPatchState {
    dlc_list: Vec<Guid>,
    mod_list: Vec<ModInfo>,
    overrides: EnumSet<OverrideType>,
}
impl NetPatchState {
    fn take(&mut self) -> NetPatchState {
        mem::replace(self, DEFAULT_PATCH_STATE)
    }
    fn reset(&mut self) {
        self.take();
    }
}

const DEFAULT_PATCH_STATE: NetPatchState =
    NetPatchState { dlc_list: Vec::new(), mod_list: Vec::new(), overrides: EnumSet::EMPTY };
static STATE: Mutex<NetPatchState> = Mutex::new(DEFAULT_PATCH_STATE);

/// Guard flag to prevent re-entry loops during override+reload cycles.
/// Set by Lua before calling the override, checked by Lua on the next
/// ActivateDLC invocation. Persists across content switches (Rust-side),
/// so it survives Lua state recreation.
static OVERRIDE_PENDING: AtomicBool = AtomicBool::new(false);

pub fn is_override_pending() -> bool {
    OVERRIDE_PENDING.load(Ordering::Acquire)
}

pub fn set_override_pending(val: bool) {
    OVERRIDE_PENDING.store(val, Ordering::Release);
}

#[derive(EnumSetType, Debug)]
pub enum OverrideType {
    OverrideDlcs,
    OverrideMods,
    ForceReloadDlcs,
    ForceReloadMods,
}

pub fn add_dlc(str: &str) {
    fn parse(str: &str) -> Option<Guid> {
        let mut parts = str.splitn(5, '-');
        let p1 = parts.next()?;
        let p2 = parts.next()?;
        let p3 = parts.next()?;
        let p4 = parts.next()?;
        let p5 = parts.next()?;
        if p5.len() != 12 {
            return None;
        }
        let data1 = u32::from_str_radix(p1, 16).ok()?;
        let data2 = u16::from_str_radix(p2, 16).ok()?;
        let data3 = u16::from_str_radix(p3, 16).ok()?;
        let g4 = u64::from_str_radix(p4, 16).ok()?;
        let g5_hi = u64::from_str_radix(&p5[..4], 16).ok()?;
        let g5_lo = u64::from_str_radix(&p5[4..], 16).ok()?;
        let data4 = (g4 << 48) | (g5_hi << 32) | g5_lo;
        Some(Guid { data1, data2, data3, data4 })
    }
    match parse(str) {
        Some(guid) => STATE.lock().unwrap().dlc_list.push(guid),
        None => log::error!("Invalid GUID format passed to pushDLC: {str}"),
    }
}
pub fn add_mod(mod_id: &str, version: i32) {
    let mut info = ModInfo { mod_id: [0; 64], version };
    let copy_len = mod_id.len().min(63);
    info.mod_id[..copy_len].copy_from_slice(&mod_id.as_bytes()[..copy_len]);
    (*STATE.lock().unwrap()).mod_list.push(info);
}
pub fn add_override(ty: OverrideType) {
    (*STATE.lock().unwrap()).overrides |= ty;
}

#[derive(Copy, Clone)]
#[repr(C)]
pub struct Guid {
    data1: u32,
    data2: u16,
    data3: u16,
    data4: u64,
}
impl Debug for Guid {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{:08x}-{:04x}-{:04x}-{:04x}-{:04x}{:08x}",
            self.data1,
            self.data2,
            self.data3,
            (self.data4 >> 48) & 0xFFFF,
            (self.data4 >> 32) & 0xFFFF,
            self.data4 as u32,
        )
    }
}

#[derive(Copy, Clone)]
#[repr(C)]
pub struct ModInfo {
    mod_id: [u8; 64],
    version: i32,
}
impl Debug for ModInfo {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} v{}",
            CStr::from_bytes_until_nul(&self.mod_id)
                .unwrap()
                .to_string_lossy(),
            self.version
        )
    }
}

pub fn install() {
    #[cfg(windows)]
    hook_install();
}
pub fn reset() {
    OVERRIDE_PENDING.store(false, Ordering::Release);
    let mut lock = STATE.lock().unwrap();
    #[cfg(windows)]
    hook_unpatch();
    lock.reset();
}

unsafe fn SetActiveDLCAndModsProxy_impl(
    this: *mut c_void,
    dlc_list: *mut CppListRaw<Guid>,
    mod_list: *mut CppListRaw<ModInfo>,
    reload_dlc: bool,
    reload_mods: bool,
) -> c_int {
    let state = {
        let mut lock = STATE.lock().unwrap();
        #[cfg(windows)]
        hook_unpatch();
        lock.take()
    };

    debug!("[SetActiveDLCAndModsProxy call begin]");
    debug!("dlc_list = {:#?}", CppList::from_raw(dlc_list));
    debug!("mod_list = {:#?}", CppList::from_raw(mod_list));
    debug!("reload_dlc = {}", reload_dlc);
    debug!("reload_mods = {}", reload_mods);
    debug!("state = {:#?}", state);

    let dlc_list = if state.overrides.contains(OverrideType::OverrideDlcs) {
        let mut list = CppList::new();
        for dlc in state.dlc_list {
            list.push(dlc);
        }
        list
    } else {
        CppList::from_raw(dlc_list)
    };
    let mod_list = if state.overrides.contains(OverrideType::OverrideMods) {
        let mut list = CppList::new();
        for entry in state.mod_list {
            list.push(entry);
        }
        list
    } else {
        CppList::from_raw(mod_list)
    };
    let reload_dlc = reload_dlc | state.overrides.contains(OverrideType::ForceReloadDlcs);
    let reload_mods = reload_mods
        | state.overrides.contains(OverrideType::ForceReloadMods)
        | state.overrides.contains(OverrideType::OverrideMods);

    let result =
        SetActiveDLCAndMods(this, dlc_list.as_raw(), mod_list.as_raw(), reload_dlc, reload_mods);

    debug!("[SetActiveDLCAndModsProxy call end]");

    result
}

#[cfg(all(windows, target_env = "msvc"))]
pub unsafe extern "thiscall-unwind" fn SetActiveDLCAndModsProxy(
    this: *mut c_void,
    dlc_list: *mut CppListRaw<Guid>,
    mod_list: *mut CppListRaw<ModInfo>,
    reload_dlc: bool,
    reload_mods: bool,
) -> c_int {
    SetActiveDLCAndModsProxy_impl(this, dlc_list, mod_list, reload_dlc, reload_mods)
}

#[cfg(all(windows, target_env = "gnu"))]
pub unsafe extern "thiscall" fn SetActiveDLCAndModsProxy(
    this: *mut c_void,
    dlc_list: *mut CppListRaw<Guid>,
    mod_list: *mut CppListRaw<ModInfo>,
    reload_dlc: bool,
    reload_mods: bool,
) -> c_int {
    SetActiveDLCAndModsProxy_impl(this, dlc_list, mod_list, reload_dlc, reload_mods)
}

#[cfg(unix)]
pub unsafe extern "C-unwind" fn SetActiveDLCAndModsProxy(
    this: *mut c_void,
    dlc_list: *mut CppListRaw<Guid>,
    mod_list: *mut CppListRaw<ModInfo>,
    reload_dlc: bool,
    reload_mods: bool,
) -> c_int {
    SetActiveDLCAndModsProxy_impl(this, dlc_list, mod_list, reload_dlc, reload_mods)
}
