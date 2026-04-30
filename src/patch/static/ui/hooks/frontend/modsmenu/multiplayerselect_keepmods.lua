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

-- Hook global tables (Modding.ActivateDLC, PreGame.ResetGameOptions)
-- These persist within the Lua state across screen changes.
-- They are re-applied after each content switch when the file is reloaded.
if _mpPatch and _mpPatch.loaded then
    -- Save the ORIGINAL C ActivateDLC before hookTable wraps Modding.
    -- Used by stagingroom_keepmods.lua to confirm mod state at game launch.
    --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ActivateDLC] Saving original Modding.ActivateDLC")
    _mpPatch._origActivateDLC = Modding.ActivateDLC

    Modding = _mpPatch.hookTable(Modding, {
        ActivateDLC = function(...)
            if _mpPatch.patch.NetPatch.isOverridePending() then
                -- overridePending=true: proxy already injected mods.
                -- The original C ActivateDLC calls SetActiveDLCAndMods
                -- internally with reload flags, which would trigger a
                -- content switch → bounce. Skip to preserve the guard.
                --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ActivateDLC] overridePending=true, SKIP (bounce guard)")
                return
            end
            --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ActivateDLC] overridePending=false, doing mod override")
            --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ActivateDLC] ContextPtr:GetID() = " .. (ContextPtr:GetID() or "nil"))
            _mpPatch.overrideModsFromActivatedList()
            _mpPatch.patch.NetPatch.overrideReloadMods(true)
            _mpPatch.patch.NetPatch.setOverridePending(true)
            Modding._super.ActivateDLC(...)
        end
    })
    PreGame = _mpPatch.hookTable(PreGame, {
        ResetGameOptions = function(...)
            --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ResetGameOptions] called")
            --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ResetGameOptions] ContextPtr:GetID() = " .. (ContextPtr:GetID() or "nil"))
            PreGame._super.ResetGameOptions(...)
            PreGame.SetPersistSettings(false)
            local mods = Modding.GetActivatedMods()
            --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ResetGameOptions] GetActivatedMods returned " .. (#mods or 0) .. " mods")
            if mods and #mods > 0 then
                for i, m in ipairs(mods) do
                    --[[DIAG]] -- _mpPatch.debugPrint("DIAG: [ResetGameOptions]   mod[" .. i .. "] = " .. (m.ID or "?") .. " v" .. (m.Version or 0))
                end
            end
            _mpPatch.enrollModsList(mods)
        end
    })
end
