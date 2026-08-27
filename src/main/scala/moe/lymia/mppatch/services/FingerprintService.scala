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

package moe.lymia.mppatch.services

import moe.lymia.mppatch.util.EncodingUtils

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Computes a canonical playset fingerprint over an enabled list of mods.
  *
  * The authoritative fingerprint serialization lives in the (Rust)
  * mppatch-core library (`mod_fingerprint.rs`): SHA-256 over the
  * canonicalized, sorted list of enabled mods (id/version/relpath). This
  * service delegates to that concept where the underlying implementation is
  * reachable; for the UI-independent Scala surface we compute a stable
  * SHA-256 over the sorted list of canonical mod paths. When the native
  * fingerprint entry point is exposed, this method should delegate to it to
  * guarantee byte-for-byte agreement with the multiplayer runtime.
  */
object FingerprintService {

  /** Computes the playset fingerprint for a list of mods.
    *
    * @param mods
    *   the installed mod paths that are currently enabled in the playset.
    * @return
    *   the hex SHA-256 fingerprint.
    */
  def fingerprint(mods: Seq[Path]): String = {
    val canonical = mods.flatMap(canonicalModPath).distinct.sorted
    val data      = canonical.mkString("\n")
    EncodingUtils.sha256_hex(data.getBytes(StandardCharsets.UTF_8))
  }

  // Stable absolute representation of a mod path. Missing/relative files are
  // dropped so the fingerprint is deterministic across machines.
  private def canonicalModPath(path: Path): Option[String] =
    try {
      val abs = if (path.isAbsolute) path else path.toAbsolutePath
      if (Files.exists(abs)) Some(abs.normalize.toString) else None
    } catch {
      case _: Exception => None
    }
}