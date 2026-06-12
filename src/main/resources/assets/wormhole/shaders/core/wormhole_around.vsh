#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 vPos;

void main() {
    // Position is a camera-relative billboard vertex; pass it through so the fragment can build the
    // view ray (camera is at the origin in this space).
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vPos = Position;
}
