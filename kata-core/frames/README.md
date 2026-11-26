# Animation frames for review

Exports from the combat deck (K): select a move, then **EXPORT FRAMES** under its preview. The game writes two files to `.minecraft/screenshots/kata/` (in the Kizuna launcher's game folder):

- `<style>_<move>_<date>.png`: 12 frames of the move, left to right, top to bottom, each labelled with its frame number and time.
- `<style>_<move>_<date>.json`: the exact pose of every frame (each bone's pitch, yaw and roll in degrees, and the body offset).

To hand them over, upload both files into this folder on GitHub (open this folder on the branch, **Add file > Upload files**, commit). Then say which move and which frames look wrong, and what they should look like.
