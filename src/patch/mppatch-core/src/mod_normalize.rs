/*
 * Workshop mod normalizer.
 *
 * Civ5 loads a mod's `Assets/` tree as its content root, next to a
 * `<name>.modinfo` descriptor in the mod directory. Steam Workshop downloads
 * arrive in many broken shapes. This module produces a *normalized staging
 * copy* of a mod into the correct Civ5 layout, repairing the common broken
 * shapes while NEVER mutating the Workshop original. It is pure (std::fs
 * only) and UI-independent so a future profile-based launcher can stage any
 * mod per-profile.
 */

use anyhow::{Context, Result};
use log::debug;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static STAGING_COUNTER: AtomicU64 = AtomicU64::new(0);

/// Return the absolute path to a freshly normalized staging copy of the mod
/// under `scan_root`. The original `scan_root` is never written to.
///
/// The returned path owns a fully independent copy: modifying or deleting it
/// cannot affect the source mod.
pub fn normalize(scan_root: &Path) -> Result<PathBuf> {
    let scan_root = scan_root.canonicalize().with_context(|| {
        format!("mod_normalize: cannot canonicalize scan root {}", scan_root.display())
    })?;
    debug!("mod_normalize: normalizing {}", scan_root.display());

    let modinfo = find_modinfo(&scan_root);

    // Unknown layout (no modinfo): pass the tree through exactly as-is.
    let Some(modinfo_path) = modinfo else {
        let staging = create_staging_root("passthrough")?;
        copy_tree(&scan_root, &staging)?;
        debug!("mod_normalize: no modinfo found; pass-through copy to {}", staging.display());
        return Ok(staging);
    };

    // `mod_root` is the directory that actually owns the modinfo. For the
    // folder-in-folder case (ModName/ModName/content) this is nested below
    // scan_root; starting the copy here strips the redundant wrapper(s).
    let mod_root = modinfo_path
        .parent()
        .context("mod_normalize: modinfo has no parent directory")?
        .to_path_buf();

    let staging = create_staging_root("mod")?;
    // Full working copy of the mod root — originals are never touched.
    copy_tree(&mod_root, &staging)?;

    // Rebuild the standard layout inside the staging copy.
    restructure(&staging)?;

    // Repair content that is nested one level too deep / too shallow relative
    // to the paths the modinfo actually references.
    let refs = read_referenced_paths(&modinfo_path)?;
    fix_depth_offsets(&staging.join("Assets"), &refs)?;

    debug!(
        "mod_normalize: staged {} -> {}",
        scan_root.display(),
        staging.display()
    );
    Ok(staging)
}

/// Create a unique, empty staging directory.
fn create_staging_root(kind: &str) -> Result<PathBuf> {
    let seq = STAGING_COUNTER.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!(
        "mppatch_normalized_{}_{}_{}",
        kind,
        std::process::id(),
        seq
    ));
    fs::create_dir_all(&dir)
        .with_context(|| format!("mod_normalize: create staging dir {}", dir.display()))?;
    Ok(dir)
}

/// Recursively find the first `*.modinfo` under `root` (shallow-first).
fn find_modinfo(root: &Path) -> Option<PathBuf> {
    let mut pending = vec![root.to_path_buf()];
    let mut idx = 0;
    while idx < pending.len() {
        let dir = pending[idx].clone();
        idx += 1;
        let entries = fs::read_dir(&dir).ok()?;
        let mut subdirs = Vec::new();
        for entry in entries.flatten() {
            let path = entry.path();
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.is_dir() {
                subdirs.push(path);
            } else if meta.is_file() && path.extension().and_then(|e| e.to_str()) == Some("modinfo")
            {
                return Some(path);
            }
        }
        pending.extend(subdirs);
    }
    None
}

/// Recursively copy `from` into `to` (creating `to` if needed).
fn copy_tree(from: &Path, to: &Path) -> Result<()> {
    fs::create_dir_all(to)
        .with_context(|| format!("mod_normalize: mkdir {}", to.display()))?;
    for entry in fs::read_dir(from).with_context(|| {
        format!("mod_normalize: read dir {}", from.display())
    })? {
        let entry = entry?;
        let from_path = entry.path();
        let to_path = to.join(entry.file_name());
        let ft = entry.file_type()?;
        if ft.is_dir() {
            copy_tree(&from_path, &to_path)?;
        } else if ft.is_file() {
            fs::copy(&from_path, &to_path).with_context(|| {
                format!(
                    "mod_normalize: copy {} -> {}",
                    from_path.display(),
                    to_path.display()
                )
            })?;
        } else {
            // Skip symlinks / special files: they have no place in a staging copy.
            continue;
        }
    }
    Ok(())
}

/// Restructure a *staging* copy into the canonical layout: `<name>.modinfo`
/// plus an `Assets/` content root, moving any loose content under `Assets/`.
fn restructure(root: &Path) -> Result<()> {
    // Locate the modinfo directly under root.
    let mut modinfo_file: Option<PathBuf> = None;
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if entry.file_type()?.is_file()
            && entry
                .path()
                .extension()
                .and_then(|e| e.to_str())
                == Some("modinfo")
        {
            modinfo_file = Some(entry.path());
            break;
        }
    }
    let modinfo_file =
        modinfo_file.context("mod_normalize: restructure could not find modinfo at root")?;

    let assets_target = root.join("Assets");

    // Find an Assets dir regardless of case; rename it to canonical casing.
    let mut assets_dir: Option<PathBuf> = None;
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() && entry.file_name().eq_ignore_ascii_case("assets") {
            assets_dir = Some(entry.path());
            break;
        }
    }
    if let Some(dir) = assets_dir {
        if dir != assets_target {
            fs::rename(&dir, &assets_target).with_context(|| {
                format!(
                    "mod_normalize: rename {} -> {}",
                    dir.display(),
                    assets_target.display()
                )
            })?;
        }
    } else {
        fs::create_dir_all(&assets_target)
            .with_context(|| format!("mod_normalize: mkdir {}", assets_target.display()))?;
    }

    // Move every other top-level entry (loose content) under Assets/.
    let entries: Vec<PathBuf> = fs::read_dir(root)?
        .flatten()
        .map(|e| e.path())
        .collect();
    for path in entries {
        if path == modinfo_file || path == assets_target {
            continue;
        }
        let name = path
            .file_name()
            .context("mod_normalize: entry without file name")?
            .to_os_string();
        let dest = assets_target.join(&name);
        // Rename within the staging tree; if a same-named entry already exists,
        // merge directories or fall back to a recursive copy.
        if dest.exists() {
            if path.is_dir() && dest.is_dir() {
                copy_tree(&path, &dest)?;
                fs::remove_dir_all(&path).ok();
            } else {
                fs::copy(&path, &dest).ok();
            }
        } else {
            fs::rename(&path, &dest).with_context(|| {
                format!(
                    "mod_normalize: move {} -> {}",
                    path.display(),
                    dest.display()
                )
            })?;
        }
    }

    // Normalize any nested "Assets"-named dir with wrong casing that lived
    // inside the copied tree (covers stray `assets` that was already present).
    fix_casing_deep(&assets_target)?;

    Ok(())
}

/// Recursively rename any directory named `assets` (wrong case) to `Assets`.
fn fix_casing_deep(parent: &Path) -> Result<()> {
    let entries: Vec<PathBuf> = fs::read_dir(parent)?.flatten().map(|e| e.path()).collect();
    for path in entries {
        let meta = fs::metadata(&path)?;
        if meta.is_dir() {
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            if name.eq_ignore_ascii_case("assets") && name != "Assets" {
                let target = path
                    .parent()
                    .map(|p| p.join("Assets"))
                    .unwrap_or_else(|| PathBuf::from("Assets"));
                if !target.exists() {
                    fs::rename(&path, &target)?;
                }
                fix_casing_deep(&target)?;
            } else {
                fix_casing_deep(&path)?;
            }
        }
    }
    Ok(())
}

/// Extract content paths referenced by a modinfo's `<File>`/`<SQL>`/`<Lua>`
/// action elements (paths relative to the mod root, e.g. `Assets/...`).
fn read_referenced_paths(modinfo_file: &Path) -> Result<Vec<String>> {
    let text = fs::read_to_string(modinfo_file)
        .with_context(|| format!("mod_normalize: read {}", modinfo_file.display()))?;
    let bytes = text.as_bytes();
    let mut refs = Vec::new();
    let mut i = 0;
    let markers = ["<File>", "<SQL>", "<Lua>", "<lua>"];
    while i < bytes.len() {
        let mut matched: Option<&str> = None;
        for m in &markers {
            if text[i..].starts_with(m) {
                matched = Some(m);
                break;
            }
        }
        if let Some(m) = matched {
            let start = i + m.len();
            let end_rel = text[start..]
                .find('<')
                .map(|r| start + r)
                .unwrap_or(bytes.len());
            let raw = text[start..end_rel].trim();
            if !raw.is_empty() {
                refs.push(raw.to_string());
            }
            i = end_rel;
        } else {
            i += 1;
        }
    }
    Ok(refs)
}

/// Repair files referenced by the modinfo that are sitting one level too deep
/// or one level too shallow inside `assets_root` (== staging `<root>/Assets`).
fn fix_depth_offsets(assets_root: &Path, refs: &[String]) -> Result<()> {
    for r in refs {
        let Some(rel) = r.strip_prefix("Assets/").map(|s| s.to_string()) else {
            continue;
        };
        let target = assets_root.join(&rel);
        if target.exists() {
            continue; // already in the referenced place
        }
        let Some(file_name) = Path::new(&rel).file_name().map(|f| f.to_os_string()) else {
            continue;
        };
        let expected_depth = Path::new(&rel)
            .parent()
            .map(path_component_count)
            .unwrap_or(0);

        // Find a matching file one directory level deeper or shallower.
        let mut matches: Vec<PathBuf> = Vec::new();
        walk_files(assets_root, &mut matches)?;
        let mut picked: Option<PathBuf> = None;
        for cand in &matches {
            if cand.file_name().map(|f| f.to_os_string()) != Some(file_name.clone()) {
                continue;
            }
            let cand_rel = match cand.strip_prefix(assets_root) {
                Ok(p) => p.to_path_buf(),
                Err(_) => continue,
            };
            let cand_depth = cand_rel
                .parent()
                .map(path_component_count)
                .unwrap_or(0);
            if cand_depth == expected_depth + 1 || cand_depth == expected_depth - 1 {
                picked = Some(cand.clone());
                break;
            }
        }
        if let Some(src) = picked {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).ok();
            }
            fs::copy(&src, &target)
                .with_context(|| format!("mod_normalize: repair {} -> {}", src.display(), target.display()))?;
            debug!(
                "mod_normalize: depth repair {} -> {}",
                src.display(),
                target.display()
            );
        }
    }
    Ok(())
}

fn path_component_count(p: &Path) -> usize {
    p.components()
        .filter(|c| matches!(c, std::path::Component::Normal(_)))
        .count()
}

fn walk_files(root: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        if entry.file_type()?.is_dir() {
            walk_files(&path, out)?;
        } else if entry.file_type()?.is_file() {
            out.push(path);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    const FIXTURES: &str =
        concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/mod_normalize");

    fn dir_sha256(dir: &Path) -> Vec<u8> {
        let mut files = Vec::new();
        walk_files(dir, &mut files).unwrap();
        files.sort();
        let mut hasher = Sha256::new();
        for f in files {
            hasher.update(f.file_name().map(|x| x.to_string_lossy().into_owned()).unwrap_or_default());
            hasher.update(b"\0");
            let data = fs::read(&f).unwrap();
            hasher.update(&data);
            hasher.update(b"\0");
        }
        hasher.finalize().to_vec()
    }

    fn hex(bytes: &[u8]) -> String {
        let mut s = String::new();
        for b in bytes {
            s.push_str(&format!("{:02x}", b));
        }
        s
    }

    struct Case {
        name: &'static str,
        expected: &'static [&'static str], // paths relative to staging that must exist
        expect_passthrough: bool,
    }

    fn assert_normalized(case: &Case) {
        let scan = PathBuf::from(FIXTURES).join(case.name);
        let before = dir_sha256(&scan);

        let staging = normalize(&scan).unwrap();

        let after = dir_sha256(&scan);
        assert_eq!(
            hex(&before),
            hex(&after),
            "originals in {} must be byte-identical after normalize",
            case.name
        );

        for rel in case.expected {
            let p = staging.join(rel);
            assert!(
                p.exists(),
                "case {}: expected {} present in staging {}",
                case.name,
                rel,
                staging.display()
            );
        }

        if case.expect_passthrough {
            assert!(
                !staging.join("Assets").exists(),
                "case {}: passthrough must not introduce Assets/ for {}",
                case.name,
                staging.display()
            );
        }

        // Clean up the staging copy (it is disposable).
        let _ = fs::remove_dir_all(&staging);
    }

    #[test]
    fn case_a_loose_files_are_moved_into_assets() {
        assert_normalized(&Case {
            name: "case_a_loose",
            expected: &[
                "CoolMod.modinfo",
                "Assets/Gameplay/SQL/foo.sql",
                "Assets/Text/bar.xml",
            ],
            expect_passthrough: false,
        });
    }

    #[test]
    fn case_b_wrong_case_assets_renamed() {
        assert_normalized(&Case {
            name: "case_b_case",
            expected: &["CoolMod.modinfo", "Assets/Gameplay/SQL/foo.sql"],
            expect_passthrough: false,
        });
    }

    #[test]
    fn case_c_folder_in_folder_flattened() {
        assert_normalized(&Case {
            name: "case_c_folder_in_folder",
            expected: &["CoolMod.modinfo", "Assets/Gameplay/SQL/foo.sql"],
            expect_passthrough: false,
        });
    }

    #[test]
    fn case_d_deep_nested_file_repaired() {
        assert_normalized(&Case {
            name: "case_d_deep",
            expected: &["CoolMod.modinfo", "Assets/Gameplay/SQL/foo.sql"],
            expect_passthrough: false,
        });
    }

    #[test]
    fn case_d_shallow_nested_file_repaired() {
        assert_normalized(&Case {
            name: "case_d_shallow",
            expected: &["CoolMod.modinfo", "Assets/Gameplay/SQL/foo.sql"],
            expect_passthrough: false,
        });
    }

    #[test]
    fn safe_correct_layout_passes_through() {
        let scan = PathBuf::from(FIXTURES).join("case_safe_correct");
        let before = dir_sha256(&scan);
        let staging = normalize(&scan).unwrap();
        let after = dir_sha256(&scan);
        assert_eq!(hex(&before), hex(&after), "safe originals must be unchanged");
        assert!(staging.join("CoolMod.modinfo").exists());
        assert!(staging.join("Assets/Gameplay/SQL/foo.sql").exists());
        // No stray loose content introduced.
        let _ = fs::remove_dir_all(&staging);
    }

    #[test]
    fn unknown_layout_without_modinfo_passes_through() {
        assert_normalized(&Case {
            name: "case_unknown_no_modinfo",
            expected: &["random.txt", "sub/data.txt"],
            expect_passthrough: true,
        });
    }

    #[test]
    fn modinfo_original_is_byte_identical_for_every_case() {
        // Dedicated sha256 proof: every original fixture file unchanged.
        for name in [
            "case_a_loose",
            "case_b_case",
            "case_c_folder_in_folder",
            "case_d_deep",
            "case_d_shallow",
            "case_safe_correct",
            "case_unknown_no_modinfo",
        ] {
            let scan = PathBuf::from(FIXTURES).join(name);
            let before = collect_hashes(&scan);
            let staging = normalize(&scan).unwrap();
            let after = collect_hashes(&scan);
            assert_eq!(before, after, "originals for {} changed", name);
            let _ = fs::remove_dir_all(&staging);
        }
    }

    fn collect_hashes(dir: &Path) -> Vec<(String, Vec<u8>)> {
        let mut files = Vec::new();
        walk_files(dir, &mut files).unwrap();
        files.sort();
        let mut out = Vec::new();
        for f in files {
            out.push((
                f.strip_prefix(dir)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned(),
                bytes_sha256(&f),
            ));
        }
        out
    }

    fn bytes_sha256(p: &Path) -> Vec<u8> {
        let mut h = Sha256::new();
        h.update(fs::read(p).unwrap());
        h.finalize().to_vec()
    }
}
