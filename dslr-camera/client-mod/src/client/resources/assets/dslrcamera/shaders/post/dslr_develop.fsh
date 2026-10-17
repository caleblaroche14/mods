#version 330

// Full-resolution "develop" pass: blends in the blurred image by circle of confusion, then applies exposure with
// highlight roll-off, lens vignetting and ISO sensor noise.

#moj_import <dslrcamera:dslr_common.glsl>

uniform sampler2D InSampler;
uniform sampler2D BlurSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float gaussianNoise(vec2 p) {
    return hash(p) + hash(p + 17.1) + hash(p + 41.7) + hash(p + 73.3) - 2.0;
}

void main() {
    vec3 sharp = texture(InSampler, texCoord).rgb;
    float size = blurRadius(sceneDistance(DepthSampler, texCoord));
    vec3 colour = sharp;
    if (size > 0.5) {
        // Four bilinear taps smooth out the grain left by the sparse half-resolution gather on large discs.
        vec2 o = Screen.xy * clamp(size * 0.1, 0.5, 2.0);
        vec3 blurred = 0.25 * (texture(BlurSampler, texCoord + vec2(o.x, o.y)).rgb
            + texture(BlurSampler, texCoord + vec2(-o.x, o.y)).rgb
            + texture(BlurSampler, texCoord + vec2(o.x, -o.y)).rgb
            + texture(BlurSampler, texCoord + vec2(-o.x, -o.y)).rgb);
        colour = mix(sharp, blurred, smoothstep(0.5, 2.0, size));
    }

    vec3 base = pow(max(colour, vec3(0.0)), vec3(2.2));
    vec3 linear = base * Exposure.x;

    // Flash: rebuild each pixel's view-space position from depth, then light it from the camera with inverse-square
    // falloff and Lambert shading (normals from screen-space derivatives, so this stays in uniform control flow).
    float z = sceneDistance(DepthSampler, texCoord);
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 viewPos = vec3(ndc.x / Flash.z, ndc.y / Flash.w, 1.0) * z;
    vec3 faceNormal = cross(dFdx(viewPos), dFdy(viewPos));
    if (Flash.x > 0.0 && z < FAR_DISTANCE * 0.5) {
        float d = max(length(viewPos), 0.3);
        float normalLength = length(faceNormal);
        float facing = normalLength > 1e-9 ? abs(dot(faceNormal / normalLength, viewPos / length(viewPos))) : 1.0;
        // Undo Minecraft's lighting to estimate the surface colour, keeping hue if a lit pixel would exceed white.
        vec3 albedo = base * Flash.y;
        float peak = max(max(albedo.r, albedo.g), albedo.b);
        albedo /= max(peak, 1.0);
        linear += albedo * (Flash.x / (d * d)) * mix(1.0, facing, 0.85);
    }

    // Overexposed highlights roll off towards white instead of hard clipping.
    vec3 knee = vec3(0.75);
    vec3 rolled = knee + (1.0 - knee) * (1.0 - exp(-(linear - knee) / (1.0 - knee)));
    vec3 compressed = mix(linear, rolled, step(knee, linear));
    linear = mix(min(linear, vec3(1.0)), compressed, max(smoothstep(1.0, 1.3, Exposure.x), step(1e-6, Flash.x)));

    vec2 centred = (texCoord - 0.5) * vec2(Screen.z, 1.0);
    float r = length(centred) / length(vec2(Screen.z, 1.0) * 0.5);
    linear *= 1.0 - Exposure.z * pow(r, 2.5);

    vec3 result = pow(clamp(linear, 0.0, 1.0), vec3(1.0 / 2.2));

    if (Exposure.y > 0.0) {
        vec2 p = gl_FragCoord.xy + Exposure.w * 113.0;
        float luma = dot(result, vec3(0.2126, 0.7152, 0.0722));
        float strength = Exposure.y * (0.35 + 0.65 * (1.0 - luma));
        float grain = gaussianNoise(p) * strength;
        vec3 chroma = vec3(gaussianNoise(p + 5.3), gaussianNoise(p + 9.1), gaussianNoise(p + 2.7)) * strength * 0.45;
        result = clamp(result + grain + chroma, 0.0, 1.0);
    }

    fragColor = vec4(result, 1.0);
}
