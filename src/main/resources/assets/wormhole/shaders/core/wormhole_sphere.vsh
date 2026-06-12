#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 vDir;

void main() {
    // Position is a UNIT sphere vertex; ModelViewMat places/scales it at the mouth, so the raw
    // Position doubles as the outward direction (used to sample the captured surroundings later).
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vDir = Position;
}
