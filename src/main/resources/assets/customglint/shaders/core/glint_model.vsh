#version 150

#moj_import <fog.glsl>

// Custom Glints MODEL GLINT vertex shader (1.21.1). Vanilla rendertype_glint.vsh, declared for NEW_ENTITY, with
// NO camera-ward depth bias (the one difference from glint_cutout).
//
// For armor whose MODEL is its own shape (an elytra): the base is drawn cutout, so its depth already marks the
// nearest surface per pixel. Testing the glint EQUAL against that depth self-occludes overlapping geometry (a
// closed elytra layers its two wings at the center), so only the nearest wing's glint draws and the one behind
// fails. A proud bias would instead let both overlapping faces pass and additively double the seam, so there is
// none here. NEW_ENTITY so the glint rides Sodium's renderCuboid alongside the base and quantizes identically,
// which is what lets the EQUAL test match under Sodium (see glint_cutout's header for the format rationale).
//
// The extra NEW_ENTITY attributes (Color/UV1/UV2/Normal) are declared only to bind the format; the shader reads
// Position and UV0.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}
