package com.dslrcamera.client.render;

import java.util.Optional;

import com.dslrcamera.client.CameraState;
import com.dslrcamera.client.DslrCameraClient;
import com.dslrcamera.client.config.DslrConfig;
import com.dslrcamera.client.exposure.ExposureSettings;
import com.dslrcamera.client.exposure.LightMeter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Applies the camera's optics to the world image while the viewfinder is up: thin-lens depth of field from the depth
 * buffer (a half-resolution bokeh pass, then a full-resolution composite), exposure, vignetting and ISO noise. It runs before the hand and GUI are drawn, so the live view and the captured
 * photo are identical.
 */
public final class DslrEffect {
	private static final float SENSOR_HEIGHT_MM = 24.0F;
	private static final int UBO_SIZE = 5 * 16;

	/** Half-resolution bokeh gather. */
	public static final RenderPipeline BLUR_PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(DslrCameraClient.id("pipeline/dslr_blur"))
		.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
		.withFragmentShader(DslrCameraClient.id("post/dslr_blur"))
		.withBindGroupLayout(BindGroupLayout.builder()
			.withSampler("InSampler")
			.withSampler("DepthSampler")
			.withUniform("DslrConfig", UniformType.UNIFORM_BUFFER)
			.build())
		.build();

	/** Full-resolution composite, exposure, vignette and noise. */
	public static final RenderPipeline DEVELOP_PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
		.withLocation(DslrCameraClient.id("pipeline/dslr_develop"))
		.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
		.withFragmentShader(DslrCameraClient.id("post/dslr_develop"))
		.withBindGroupLayout(BindGroupLayout.builder()
			.withSampler("InSampler")
			.withSampler("BlurSampler")
			.withSampler("DepthSampler")
			.withUniform("DslrConfig", UniformType.UNIFORM_BUFFER)
			.build())
		.build();

	private static MappableRingBuffer ubo;
	private static TextureTarget scratch;
	private static TextureTarget blurred;
	private static int frame;

	private DslrEffect() {
	}

	public static void register() {
		RenderPipelines.register(BLUR_PIPELINE);
		RenderPipelines.register(DEVELOP_PIPELINE);
	}

	/** Linear brightness multiplier for the current settings and metered scene. */
	public static double exposureGain() {
		double sceneEv = LightMeter.sceneEv();
		double stops = sceneEv - ExposureSettings.settingsEv();
		return Math.clamp(LightMeter.renderGain(sceneEv) * Math.pow(2, stops), 1 / 256.0, 256.0);
	}

	public static void apply(RenderTarget main, Matrix4f projection) {
		int width = main.width;
		int height = main.height;
		if (main.getColorTexture() == null || main.getDepthTexture() == null) {
			return;
		}
		if (ubo == null) {
			ubo = new MappableRingBuffer(() -> "DSLR camera config", 130, UBO_SIZE);
		}
		scratch = ensureTarget(scratch, "DSLR camera scratch", width, height);
		blurred = ensureTarget(blurred, "DSLR camera bokeh", Math.max(1, width / 2), Math.max(1, height / 2));

		float focal = CameraState.targetFocal();
		float fNumber = (float) ExposureSettings.fNumber();
		float focus = (float) LightMeter.focusDistance();
		float pixelsPerMm = height / SENSOR_HEIGHT_MM;
		// Strength exaggerates the thin-lens blur: Minecraft scenes are small and blocky, so true-to-life bokeh reads as subtle.
		float strength = DslrConfig.getFloat(DslrConfig.Option.DOF_STRENGTH);
		float maxBlur = Math.min(DslrConfig.getFloat(DslrConfig.Option.MAX_BLUR) * height / 1080.0F, strength * Math.max(
			blurRadius(0.5F, focal, fNumber, focus, pixelsPerMm), blurRadius(100000.0F, focal, fNumber, focus, pixelsPerMm)));
		double isoStops = Math.max(0, Math.log(ExposureSettings.iso() / 100.0) / Math.log(2));
		float noise = (float) (0.012 * Math.pow(isoStops, 1.5));
		float vignette = 0.05F + 0.4F * Math.clamp((4.0F - fNumber) / 2.6F, 0.0F, 1.0F);
		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();

		try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data())
				.putVec4((float) exposureGain(), noise, vignette, (frame++ % 1024) / 1024.0F)
				.putVec4(focal, fNumber, focus, pixelsPerMm)
				.putVec4(projection.m22(), projection.m32(), zeroToOne ? 1.0F : 0.0F, maxBlur)
				.putVec4(1.0F / width, 1.0F / height, (float) width / height, strength)
				.putVec4(DslrConfig.getFloat(DslrConfig.Option.HIGHLIGHT_THRESHOLD), DslrConfig.getFloat(DslrConfig.Option.HIGHLIGHT_BOOST), 0.0F, 0.0F);
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = encoder.createRenderPass(() -> "DSLR camera bokeh", blurred.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(BLUR_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DslrConfig", ubo.currentBuffer());
			pass.bindTexture("InSampler", main.getColorTextureView(), linear);
			pass.bindTexture("DepthSampler", main.getDepthTextureView(), nearest);
			pass.draw(3, 1, 0, 0);
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "DSLR camera develop", scratch.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(DEVELOP_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DslrConfig", ubo.currentBuffer());
			pass.bindTexture("InSampler", main.getColorTextureView(), linear);
			pass.bindTexture("BlurSampler", blurred.getColorTextureView(), linear);
			pass.bindTexture("DepthSampler", main.getDepthTextureView(), nearest);
			pass.draw(3, 1, 0, 0);
		}
		ubo.rotate();
		encoder.copyTextureToTexture(scratch.getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, width, height);
	}

	private static TextureTarget ensureTarget(TextureTarget target, String label, int width, int height) {
		if (target == null) {
			return new TextureTarget(label, width, height, false, GpuFormat.RGBA8_UNORM);
		}
		if (target.width != width || target.height != height) {
			target.resize(width, height);
		}
		return target;
	}

	private static float blurRadius(float distance, float focal, float fNumber, float focus, float pixelsPerMm) {
		float aperture = focal / fNumber;
		float s = focus * 1000.0F;
		float d = distance * 1000.0F;
		float diameter = aperture * focal * Math.abs(d - s) / (d * Math.max(s - focal, 1.0F));
		return diameter * pixelsPerMm * 0.5F;
	}
}
