#version 330

// Half-resolution bokeh gather: each pixel averages a disk the size of its own circle of confusion, skipping
// samples whose own blur doesn't reach it (so sharp foreground never smears into a blurred background and vice versa).

#moj_import <dslrcamera:dslr_common.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;
out vec4 fragColor;

const int MIN_SAMPLES = 24;
const int MAX_SAMPLES = 160;
const float GOLDEN_ANGLE = 2.39996323;
// Bright samples are weighted up so light sources and sky gaps spread into distinct bokeh discs instead of
// dissolving into grey, approximating the HDR light a real sensor would capture. Tuned via Bokeh (see DslrConfig).
float highlightWeight(vec3 c) {
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float h = smoothstep(Bokeh.x, 1.0, luma);
    return 1.0 + Bokeh.y * h * h;
}

void main() {
    vec3 centre = texture(InSampler, texCoord).rgb;
    float centreDist = sceneDistance(DepthSampler, texCoord);
    float centreSize = blurRadius(centreDist);
    if (centreSize < 0.75) {
        fragColor = vec4(centre, 1.0);
        return;
    }

    // Larger discs need more taps to stay smooth; small ones stay cheap.
    int samples = int(clamp(centreSize * 2.0, float(MIN_SAMPLES), float(MAX_SAMPLES)));
    // Per-pixel rotation turns sampling patterns into fine grain instead of visible ghost copies.
    float rotation = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) * 6.2831853;
    float spacing = centreSize / sqrt(float(samples));
    float centreWeight = highlightWeight(centre);
    vec3 colour = centre * centreWeight;
    float total = centreWeight;
    for (int i = 0; i < samples; i++) {
        float r = centreSize * sqrt((float(i) + 0.5) / float(samples));
        float a = float(i) * GOLDEN_ANGLE + rotation;
        vec2 uv = texCoord + vec2(cos(a), sin(a)) * r * Screen.xy;
        float sampleDist = sceneDistance(DepthSampler, uv);
        float sampleSize = blurRadius(sampleDist);
        if (sampleDist > centreDist) {
            sampleSize = min(sampleSize, centreSize * 2.0);
        }
        vec3 sampleColour = texture(InSampler, uv).rgb;
        float weight = smoothstep(r - spacing, r + spacing, sampleSize) * highlightWeight(sampleColour);
        colour += sampleColour * weight;
        total += weight;
    }
    fragColor = vec4(colour / total, 1.0);
}
