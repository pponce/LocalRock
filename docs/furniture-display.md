# Furniture on the map

The Furniture layer displays supported saved furniture independently of detected obstacles.
Legacy S7 MaxV furniture records with verified type/subtype pairs show top-down symbols:
sofa (46/3), armchair (46/1), dining set (47/0), and bed (45/1). Unknown pairs retain a neutral
footprint instead of being assigned a guessed furniture type.

Symbols follow the record's four corners, so saved position, dimensions and rotation are
preserved. These are LocalRock vector illustrations, not OEM 3D assets. Hidden records
remain hidden, and unrecognized record layouts are retained without speculative decoding.

This layer is read-only. Moving, rotating or resizing furniture in LocalRock is not yet
supported. Change saved furniture in the OEM editor and refresh the map in LocalRock.
Furniture display does not change cleaning targets, room boundaries or restrictions.
