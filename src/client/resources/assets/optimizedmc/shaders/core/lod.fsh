#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

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
    vec4 color = vec4(vertexColor.rgb * ColorModulator.rgb, 1.0);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
