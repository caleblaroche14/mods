#version 330

// Half-resolution bokeh gather: each pixel averages a disk the size of its own circle of confusion, skipping
// samples whose own blur doesn't reach it (so sharp foreground never smears into a blurred background and vice versa).

#moj_import <dslrcamera:dslr_common.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;
out vec4 fragColor;

const int SAMPLES = 64;
const float GOLDEN_ANGLE = 2.39996323;

void main() {
    vec3 centre = texture(InSampler, texCoord).rgb;
    float centreDist = sceneDistance(DepthSampler, texCoord);
    float centreSize = blurRadius(centreDist);
    if (centreSize < 0.75) {
        fragColor = vec4(centre, 1.0);
        return;
    }

    // Per-pixel rotation turns sampling patterns into fine grain instead of visible ghost copies.
    float rotation = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) * 6.2831853;
    float spacing = centreSize / sqrt(float(SAMPLES));
    vec3 colour = centre;
    float total = 1.0;
    for (int i = 0; i < SAMPLES; i++) {
        float r = centreSize * sqrt((float(i) + 0.5) / float(SAMPLES));
        float a = float(i) * GOLDEN_ANGLE + rotation;
        vec2 uv = texCoord + vec2(cos(a), sin(a)) * r * Screen.xy;
        float sampleDist = sceneDistance(DepthSampler, uv);
        float sampleSize = blurRadius(sampleDist);
        if (sampleDist > centreDist) {
            sampleSize = min(sampleSize, centreSize * 2.0);
        }
        float weight = smoothstep(r - spacing, r + spacing, sampleSize);
        colour += texture(InSampler, uv).rgb * weight;
        total += weight;
    }
    fragColor = vec4(colour / total, 1.0);
}
