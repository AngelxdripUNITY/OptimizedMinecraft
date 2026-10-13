#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler2;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 horizontalOffset;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    // ColorModulator.a holds the radius already covered by real chunks; LOD stays out of it.
    if (length(horizontalOffset) < ColorModulator.a) {
        discard;
    }
    // Light it exactly like a real surface block in full sky light (sky 15, block 0): the
    // lightmap carries time of day, gamma, night vision and lightning flashes.
    vec4 light = texture(Sampler2, vec2(0.5 / 16.0, 15.5 / 16.0));
    vec4 color = vec4(vertexColor.rgb * light.rgb * ColorModulator.rgb, 1.0);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
