#version 150

#moj_import <fog.glsl>

// Custom Glints PROCEDURAL CHROMATIC vertex shader (1.21.1 port of the 26.1 core/chromatic.vsh). Same
// skeleton as vanilla rendertype_glint.vsh, but the chromatic design has no texture: the fragment shader
// synthesises an oil-slick from value-noise. The per-layer payload that the RenderType can't carry as a
// uniform rides spare slots of the TextureMat (fed by CustomGlintRenderer's chromatic texture matrix):
//   TextureMat[2][3] = per-trim seed   (decorrelates each trim's pattern — "no two look alike")
//   TextureMat[2][0] = morph speed     (scales the GameTime-driven flow)
//   TextureMat[2][1] = colour count    (0 => rainbow fallback; 1..8 => palette texels)
// The 2D part of TextureMat still scales/positions the noise UV exactly like the normal glint.

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec2 noiseCoord;
out float vSeed;
out float vMorph;
out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    // noiseCoord = the scaled item/model UV. The renderer's texture matrix bakes patternScale * uvScale into
    // the 2D part; for flat items uvScale = atlasW/16 cancels the block-atlas sprite compression so the sprite
    // spans patternScale UV units (× DENSITY in the fsh → a constant ~7 cells/icon, matching 26.1.2).
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
