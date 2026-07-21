#version 150

// Custom Glints CHROMATIC BAKE vertex shader. Draws one screen-space quad into ChromaticTextureBaker's offscreen
// target; the fragment shader synthesises the oil-slick across it. The result is then an ordinary glint texture,
// so chromatic rides the same RenderTypes as the 54 PNG designs (see ChromaticTextureBaker for why).
//
// TextureMat is NOT a transform here - the bake's UV needs no scaling (the fsh spreads DENSITY cells across 0..1
// itself), so all 16 slots are free and these are read as plain uniform elements. Keep in step with
// ChromaticTextureBaker.payload():
//   TextureMat[0][0..1] = seed offset   (decorrelates each trim's pattern - "no two look alike")
//   TextureMat[1][0..1] = field-1 flow  (wrapped to the noise period on the CPU)
//   TextureMat[2][0..1] = field-2 flow  (wrapped to 2x the period - that field samples at 2x frequency)
//   TextureMat[3][0]    = colour count  (0 => rainbow fallback; 1..8 => palette texels)
//   TextureMat[3][1]    = hue phase     (the slow colour drift, 0..1)

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

out vec2 bakeUV;
out vec2 vSeed;
out vec2 vFlow1;
out vec2 vFlow2;
out float vCount;
out float vHue;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    bakeUV = UV0;
    vSeed  = vec2(TextureMat[0][0], TextureMat[0][1]);
    vFlow1 = vec2(TextureMat[1][0], TextureMat[1][1]);
    vFlow2 = vec2(TextureMat[2][0], TextureMat[2][1]);
    vCount = TextureMat[3][0];
    vHue   = TextureMat[3][1];
}
