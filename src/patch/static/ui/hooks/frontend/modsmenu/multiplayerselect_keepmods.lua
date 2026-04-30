-- Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
--
-- Permission is hereby granted, free of charge, to any person obtaining a copy
-- of this software and associated documentation files (the "Software"), to deal
-- in the Software without restriction, including without limitation the rights
-- to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
-- copies of the Software, and to permit persons to whom the Software is
-- furnished to do so, subject to the following conditions:
--
-- The above copyright notice and this permission notice shall be included in
-- all copies or substantial portions of the Software.
--
-- THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
-- IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
-- FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
-- AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
-- LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
-- OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
-- THE SOFTWARE.

if _mpPatch and _mpPatch.loaded and ContextPtr:GetID() == "ModMultiplayerSelectScreen" then
    Modding = _mpPatch.hookTable(Modding, {
        ActivateDLC = function(...)
            -- Check the Rust-side guard to avoid re-entry loops:
            -- if an override+reload is already in progress from a previous
            -- ActivateDLC call, skip the override and let the game proceed.
            if not _mpPatch.patch.NetPatch.isOverridePending() then
                _mpPatch.overrideModsFromActivatedList()
                _mpPatch.patch.NetPatch.overrideReloadMods(true)
                _mpPatch.patch.NetPatch.setOverridePending(true)
            end
            Modding._super.ActivateDLC(...)
        end
    })
    PreGame = _mpPatch.hookTable(PreGame, {
        ResetGameOptions = function(...)
            PreGame._super.ResetGameOptions(...)
            PreGame.SetPersistSettings(false)
            _mpPatch.enrollModsList(Modding.GetActivatedMods())
        end
    })
end
