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

//! MP mod fingerprint guard.
//!
//! Produces a SHA-256 over the *canonical* enabled-mod list so that every
//! peer in a multiplayer lobby can derive the same digest and agree on whether
//! their mod sets are in sync.
//!
//! Canonicalization: mods are sorted by `(id, version)` and each mod's files
//! are sorted by `relpath` before any hashing happens. Field lengths are
//! length-prefixed when serialized into the digest buffer, so two different
//! lists cannot collide on framing ambiguity (e.g. id `"ab"` + file `"c"` vs
//! id `"a"` + file `"bc"`).
//!
//! # Installer wiring (out of scope here)
//!
//! The installer side will expose this to the Lua/UI layer as `mpPatch`
//! (`Lua` calls it, e.g. `mpPatch.modsGetFingerprint()`), returning a JSON
//! object built from [`ModFingerprint`] via its `serde::Serialize` impl:
//!
//! ```json
//! { "hash": "e3b0c442...", "mod_count": 0 }
//! ```
//!
//! The lobby then compares the `hash` string across clients and displays a
//! sync **OK** when they all match, or **MISMATCH** when any differ. The exact
//! host-to-peer handshake and Lua table plumbing live in the installer layer,
//! not this module.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// One enabled civ mod: its stable identifier, version, and the set of files it
/// ships, each with a SHA-256 digest of the file's content.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModEntry {
    pub id: String,
    pub version: String,
    /// `(relative path, sha256 of file content)` — path length is in bytes,
    /// the digest is the raw 32 bytes.
    pub files: Vec<(String, [u8; 32])>,
}

impl ModEntry {
    /// Convenience constructor used throughout the tests and by callers that
    /// already have hex digests (each `files` entry is `(path, hex)`).
    pub fn new(id: &str, version: &str, files: Vec<(String, [u8; 32])>) -> Self {
        ModEntry {
            id: id.to_string(),
            version: version.to_string(),
            files,
        }
    }
}

/// SHA-256 over the canonicalized enabled-mod list — the mod fingerprint guard.
///
/// Returns the raw 32-byte digest. See the module docs for the canonicalization
/// rules. For a JSON-ready transport wrapper, see [`ModFingerprint::compute`].
pub fn fingerprint(mods: &[ModEntry]) -> [u8; 32] {
    // Fast path: empty set hashes to the canonical SHA-256 of the empty string.
    if mods.is_empty() {
        return sha256_of(b"");
    }

    // 1. Normalize each mod's file list into a sorted copy.
    let mut sorted: Vec<(ModEntry, Vec<(String, [u8; 32])>)> = mods
        .iter()
        .map(|m| {
            let mut files = m.files.clone();
            files.sort_by(|a, b| a.0.cmp(&b.0));
            (m.clone(), files)
        })
        .collect();
    // 2. Sort mods by (id, version).
    sorted.sort_by(|(a, _), (b, _)| (&a.id, &a.version).cmp(&(&b.id, &b.version)));

    // 3. Serialize into a length-prefixed canonical buffer, then hash it.
    let mut canonical: Vec<u8> = Vec::new();
    for (m, files) in &sorted {
        write_field(&mut canonical, &m.id);
        write_field(&mut canonical, &m.version);
        for (relpath, fhash) in files {
            write_field(&mut canonical, relpath);
            canonical.extend_from_slice(fhash);
        }
    }
    sha256_buf(&canonical)
}

/// Length-prefixed (u32 LE) string field, so framing is unambiguous.
fn write_field(buf: &mut Vec<u8>, s: &str) {
    buf.extend_from_slice(&(s.len() as u32).to_le_bytes());
    buf.extend_from_slice(s.as_bytes());
}

fn sha256_buf(data: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hasher.finalize().into()
}

/// Convenience wrapper: hash an in-memory byte slice (used for the empty-list case).
fn sha256_of(data: &[u8]) -> [u8; 32] {
    sha256_buf(data)
}

/// Transport/display wrapper for the fingerprint, serializable to JSON so the
/// Lua/UI layer can render a sync OK/MISMATCH badge in the lobby.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModFingerprint {
    /// Hex-encoded 64-char SHA-256 of the canonical mod list.
    pub hash: String,
    /// Number of enabled mods that were fingerprinted.
    pub mod_count: usize,
}

impl ModFingerprint {
    /// Compute the fingerprint for an enabled-mod list as a transport-ready value.
    pub fn compute(mods: &[ModEntry]) -> Self {
        ModFingerprint {
            hash: hex_string(&fingerprint(mods)),
            mod_count: mods.len(),
        }
    }
}

/// RFC-4648-free, dependency-free hex encoding of a 32-byte digest.
fn hex_string(digest: &[u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

/// SHA-256 of an empty byte slice — the expected digest for an empty mod list.
fn sha256_empty() -> [u8; 32] {
    sha256_buf(&[])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, content_byte: u8) -> (String, [u8; 32]) {
        (path.to_string(), [content_byte; 32])
    }

    fn sample_entries() -> Vec<ModEntry> {
        vec![
            ModEntry::new("mod-a", "1.0", vec![file("Units.xml", 0xaa)]),
            ModEntry::new(
                "mod-b",
                "2.0",
                vec![
                    file("Data/Game.xml", 0xbb),
                    file("Text/en_US.xml", 0xcc),
                ],
            ),
        ]
    }

    #[test]
    fn same_set_different_order_same_hash() {
        let ordered = sample_entries();
        let mut reversed = ordered.clone();
        reversed.reverse();
        assert_eq!(fingerprint(&ordered), fingerprint(&reversed));
    }

    #[test]
    fn version_bump_changes_hash() {
        let v1 = sample_entries();
        let mut v2 = v1.clone();
        v2[0].version = "Scenario_v2".to_string();
        assert_ne!(fingerprint(&v1), fingerprint(&v2));
    }

    #[test]
    fn file_content_change_changes_hash() {
        let base = sample_entries();
        let mut changed = base.clone();
        changed[0].files[0].1 = [0xff; 32]; // same path, different content hash
        assert_ne!(fingerprint(&base), fingerprint(&changed));
    }

    #[test]
    fn file_list_order_does_not_change_hash() {
        // Files vec order is irrelevant because canonicalization re-sorts.
        let base = sample_entries();
        let mut swapped = base.clone();
        swapped[1].files.swap(0, 1);
        assert_eq!(fingerprint(&base), fingerprint(&swapped));
    }

    #[test]
    fn empty_list_has_stable_known_hash() {
        assert_eq!(fingerprint(&[]), sha256_empty());
        assert_eq!(fingerprint(&[]), fingerprint(&[]));
        // Known constant: SHA-256 of empty string.
        assert_eq!(
            hex_string(&fingerprint(&[])),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".to_string()
        );
    }

    #[test]
    fn transport_wrapper_serializes_to_expected_json() {
        let fp = ModFingerprint::compute(&[]);
        let json = serde_json::to_string(&fp).unwrap();
        assert_eq!(
            json,
            "{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\
             \"mod_count\":0}"
        );
    }
}