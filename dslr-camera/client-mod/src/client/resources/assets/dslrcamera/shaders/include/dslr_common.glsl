// Shared by the DSLR camera passes.

layout(std140) uniform DslrConfig {
    vec4 Exposure; // x: linear gain, y: noise strength, z: vignette strength, w: noise seed
    vec4 Lens;     // x: focal length (mm), y: f-number, z: focus distance (m), w: full-res pixels per sensor mm
    vec4 Depth;    // x: projection m22, y: projection m32, z: 1 if NDC depth is [0,1], w: max blur radius (full-res px)
    vec4 Screen;   // xy: full-res texel size, z: aspect ratio, w: depth of field strength multiplier (0 disables)
    vec4 Bokeh;    // x: highlight luma threshold, y: highlight weight boost
};

const float FAR_DISTANCE = 100000.0;

float sceneDistance(sampler2D depthSampler, vec2 uv) {
    float d = texture(depthSampler, uv).r;
    float ndc = Depth.z > 0.5 ? d : d * 2.0 - 1.0;
    float denom = ndc + Depth.x;
    if (abs(denom) < 1e-7) {
        return FAR_DISTANCE;
    }
    float dist = Depth.y / denom;
    return dist <= 0.0 ? FAR_DISTANCE : min(dist, FAR_DISTANCE);
}

// Thin-lens circle of confusion radius, in full-resolution pixels, for an object at `dist` metres.
float blurRadius(float dist) {
    if (Screen.w <= 0.0) {
        return 0.0;
    }
    float f = Lens.x;
    float aperture = f / Lens.y;
    float s = Lens.z * 1000.0;
    float d = dist * 1000.0;
    float diameterMm = aperture * f * abs(d - s) / (d * max(s - f, 1.0));
    return min(diameterMm * Lens.w * 0.5 * Screen.w, Depth.w);
}
