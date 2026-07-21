#version 150

#moj_import <fog.glsl>

// Byte-for-byte vanilla 1.20.1 rendertype_glint.vsh. We ship our own copy of the program only so we can
// declare a different blend in glint.json - see the LOAD-BEARING note in CustomGlintRenderer. Keep this in
// sync with vanilla if it ever changes; the look is supposed to be identical.

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}
