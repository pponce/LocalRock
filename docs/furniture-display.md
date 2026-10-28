# Furniture on the map

The Furniture layer and editor use all 21 S7 MaxV picker choices: two beds, TV stand,
three sofas, two chaise sofas, dining set, square and coffee tables, shoe rack,
wardrobe, nightstand, two litter boxes, pet cage, pet bed, pet bowls, floor mirror,
and cat tower. These are LocalRock vector illustrations, not OEM assets. Their
saved four-corner footprints control position, rotation and dimensions.

Open **Edit map → Furniture**. The editor loads a fresh map. Add an item from the
catalog, tap or choose it from **Items**, drag to move, and use the rotation,
size and 5 cm placement controls. Delete removes it from the local draft. **Save**
writes the changes and checks the returned map; **Cancel** discards local edits.
Small-screen controls scroll without covering the whole map. Starting dimensions
are convenient LocalRock sizes based on observed saved footprints; they are not
verified OEM defaults or minimum/maximum limits.

This first version supports the verified legacy 23-byte furniture layout with
stable object IDs and one saved map (map flag 0). It requires a finished cleaning
task and a loaded saved map. Unknown record layouts block editing. Hidden,
unknown-type, or malformed furniture stays untouched and is never included in
an edit payload. Unknown visible types keep a neutral footprint.

Saves use the observed `start_edit_map` / `save_furnitures` / `end_edit_map`
sequence. The payload contains only add/update/delete operations, using byte 21
as the persistent ID rather than a list index. Fresh status, map-list and map
reads precede each save. A changed base map aborts the write. Session cleanup is
attempted even if saving is cancelled or fails. Lost acknowledgements never
cause an automatic write retry; the editor requires an explicit reload before
another save. Success requires fresh map readback including preservation of
untouched and hidden records. Restriction sets and room definitions are not
rewritten by furniture editing.

The catalog and wire format are grounded in OEM captures and final map readback.
In-place rotation/movement were observed; resizing uses that same corner-update
format and still needs LocalRock device validation. LocalRock add, resize,
rotate, move, delete, Cancel, reconnect and readback behavior must be validated
on hardware before upstream submission. Camera signaling and general map UI
changes remain on their separate feature branches.
