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

import java.nio.file.Path

/** Stages mod copies for a profile's layout under a target directory.
  *
  * This is the seam where repair/staging logic will live in a later milestone.
  * The signature is locked now so that the CLI and future launcher can depend
  * on it unconditionally.
  */
object StageService {

  /** Stages the given mods for {@code profileId} under {@code targetRoot}.
    *
    * @param profileId
    *   the launcher profile whose layout the staged copies follow.
    * @param mods
    *   the installed mod package paths to stage.
    * @param targetRoot
    *   the directory under which the staged layout is created.
    * @return
    *   `Right` with the created staging directory on success, `Left` with the
    *   aggregated errors on failure.
    */
  def stage(profileId: String, mods: Seq[Path], targetRoot: Path): Either[ErrList, Path] =
    // TODO(W06): place mod copies into a layout for `profileId` under
    // `targetRoot` (repair/validation logic to be added). For now the staging
    // layout is intentionally not created.
    Left(ErrList.single(s"mod staging not yet implemented for profile '$profileId'"))
}