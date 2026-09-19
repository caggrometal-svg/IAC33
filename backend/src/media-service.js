import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { mediaPersistenceEnabled, persistAsset, readPersistedAsset, persistMediaJob, readPersistedJob, markInterruptedMediaJobs } from './media-storage.js';

const ROOT = path.resolve(process.env.IAC33_MEDIA_ROOT || path.join(os.tmpdir(), 'iac33-media'));
const MAX_ASSET_BYTES = 25 * 1024 * 1024;
const MAX_IMAGE_BYTES = 12 * 1024 * 1024;
const MAX_VIDEO_SECONDS = 10;
const ASSET_TTL_MS = 6 * 60 * 60 * 1000;
const jobs = new Map();
const assets = new Map();
const mediaWorkers = new Map();
const MEDIA_MAX_ACTIVE_OR_QUEUED = Math.min(Math.max(Number(process.env.IAC33_MEDIA_MAX_JOBS || 3), 1), 6);
const MEDIA_MAX_RUNNING = 1;
let mediaRunning = 0;

const IMAGE_PROVIDERS = String(process.env.IAC33_IMAGE_PROVIDERS || 'openai,stability')
  .split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
const VIDEO_PROVIDERS = String(process.env.IAC33_VIDEO_PROVIDERS || 'runway,luma,kling')
  .split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
const TTS_PROVIDERS = String(process.env.IAC33_TTS_PROVIDERS || 'openai,elevenlabs')
  .split(',').map(s => s.trim().toLowerCase()).filter(Boolean);

function providerError(status, message, code = 'MEDIA_PROVIDER_ERROR') {
  const e = new Error(message);
  e.status = status;
  e.code = code;
  return e;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function assertPrompt(prompt) {
  if (typeof prompt !== 'string' || !prompt.trim() || prompt.length > 8000) {
    throw providerError(400, 'Invalid prompt', 'INVALID_MEDIA_PROMPT');
  }
  return prompt.trim();
}

function assertImageData(value) {
  if (typeof value !== 'string' || !/^data:image\/(?:png|jpe?g|webp);base64,[A-Za-z0-9+/=]+$/i.test(value)) {
    throw providerError(400, 'Invalid image input', 'INVALID_MEDIA_IMAGE');
  }
  const comma = value.indexOf(',');
  const bytes = Buffer.byteLength(value.slice(comma + 1), 'base64');
  if (bytes > MAX_IMAGE_BYTES) throw providerError(413, 'Image input too large', 'MEDIA_IMAGE_TOO_LARGE');
  return value;
}

function aspectRatio(value) {
  const allowed = new Set(['1024x1024', '1536x1024', '1024x1536', '1280:720', '720:1280', '1080:1080']);
  return allowed.has(value) ? value : '1024x1024';
}

function videoRatio(value) {
  const allowed = new Set(['1280:720', '720:1280', '1080:1080']);
  return allowed.has(value) ? value : '1280:720';
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 30_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (error) {
    if (error?.name === 'AbortError') throw providerError(504, 'Provider timeout', 'MEDIA_PROVIDER_TIMEOUT');
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function responseBytes(response, maxBytes = MAX_ASSET_BYTES) {
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) {
    throw providerError(502, 'Provider response too large', 'MEDIA_PROVIDER_RESPONSE_TOO_LARGE');
  }
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.length > maxBytes) {
    throw providerError(502, 'Provider response too large', 'MEDIA_PROVIDER_RESPONSE_TOO_LARGE');
  }
  return buffer;
}

async function responseJson(response) {
  const text = await response.text();
  try { return text ? JSON.parse(text) : {}; }
  catch { return {}; }
}

async function ensureRoot() {
  await fs.mkdir(ROOT, { recursive: true });
}

async function saveAsset(buffer, mimeType, extension) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0 || buffer.length > MAX_ASSET_BYTES) {
    throw providerError(502, 'Invalid generated asset', 'INVALID_MEDIA_ASSET');
  }
  const id = crypto.randomUUID();
  const createdAt = Date.now();
  if (mediaPersistenceEnabled()) {
    const persisted = await persistAsset({ id, buffer, mimeType, extension, createdAt });
    assets.set(id, { filePath: null, mimeType, createdAt, persistent: true, objectKey: persisted.objectKey });
    return { id, filePath: null, mimeType };
  }
  await ensureRoot();
  const filename = id + '.' + extension.replace(/^\./, '');
  const filePath = path.join(ROOT, filename);
  await fs.writeFile(filePath, buffer, { mode: 0o600 });
  assets.set(id, { filePath, mimeType, createdAt, persistent: false });
  return { id, filePath, mimeType };
}

function assetUrl(id) {
  return '/v1/ai/media/assets/' + encodeURIComponent(id);
}

function cleanupExpiredMediaJobs(now = Date.now()) {
  for (const [id, job] of jobs) {
    if (now - job.updatedAt > ASSET_TTL_MS) {
      jobs.delete(id);
      mediaWorkers.delete(id);
    }
  }
}

function scheduleMediaJobs() {
  cleanupExpiredMediaJobs();
  while (mediaRunning < MEDIA_MAX_RUNNING) {
    const next = [...jobs.values()].find((job) => job.status === 'QUEUED' && mediaWorkers.has(job.id));
    if (!next) break;
    const worker = mediaWorkers.get(next.id);
    mediaRunning += 1;
    next.status = 'RUNNING';
    next.updatedAt = Date.now();
    void persistMediaJob(next);

    Promise.resolve()
      .then(worker)
      .catch((error) => {
        next.status = 'FAILED';
        next.errorCode = error?.code || 'MEDIA_JOB_ERROR';
        next.updatedAt = Date.now();
        void persistMediaJob(next);
        console.error('IAC33 media job failed:', error?.code || 'MEDIA_JOB_ERROR');
      })
      .finally(() => {
        mediaWorkers.delete(next.id);
        mediaRunning = Math.max(0, mediaRunning - 1);
        scheduleMediaJobs();
      });
  }
}

async function enqueueMediaJob(job, worker) {
  cleanupExpiredMediaJobs();
  const activeOrQueued = [...jobs.values()].filter((item) =>
    item.status === 'QUEUED' || item.status === 'RUNNING'
  ).length;
  if (activeOrQueued >= MEDIA_MAX_ACTIVE_OR_QUEUED) {
    throw providerError(503, 'Multimedia queue is busy', 'MEDIA_QUEUE_BUSY');
  }
  await persistMediaJob(job);
  jobs.set(job.id, job);
  mediaWorkers.set(job.id, worker);
  scheduleMediaJobs();
  return job;
}

function jobPublic(job) {
  return {
    jobId: job.id,
    kind: job.kind,
    status: job.status,
    provider: job.provider || null,
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
    error: job.status === 'FAILED' ? 'MEDIA_GENERATION_FAILED' : undefined,
    assetUrl: job.assetId ? assetUrl(job.assetId) : null
  };
}

async function openAiImage(prompt) {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw providerError(503, 'OpenAI image provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const model = process.env.OPENAI_IMAGE_MODEL || 'gpt-image-2.5-sunburst';
  const response = await fetchWithTimeout('https://api.openai.com/v1/images/generations', {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key, 'content-type': 'application/json' },
    body: JSON.stringify({
      model,
      prompt,
      size: aspectRatio(process.env.OPENAI_IMAGE_SIZE),
      n: 1,
      output_format: 'png'
    })
  }, 60_000);
  const json = await responseJson(response);
  if (!response.ok) throw providerError(response.status, 'OpenAI image request failed');
  const item = json?.data?.[0];
  if (!item?.b64_json) throw providerError(502, 'OpenAI image returned no binary output');
  return { buffer: Buffer.from(item.b64_json, 'base64'), mimeType: 'image/png', extension: 'png', provider: 'openai' };
}

async function openAiImageEdit(prompt, imageData) {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw providerError(503, 'OpenAI image provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const model = process.env.OPENAI_IMAGE_MODEL || 'gpt-image-2.5-sunburst';
  const match = imageData.match(/^data:(image\/[a-z0-9.+-]+);base64,(.+)$/i);
  const mime = match?.[1] || 'image/png';
  const raw = match?.[2] || '';
  const ext = mime.includes('jpeg') ? 'jpg' : mime.split('/')[1].split('+')[0];
  const form = new FormData();
  form.append('model', model);
  form.append('prompt', prompt);
  form.append('image[]', new Blob([Buffer.from(raw, 'base64')], { type: mime }), 'input.' + ext);
  form.append('output_format', 'png');
  const response = await fetchWithTimeout('https://api.openai.com/v1/images/edits', {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key },
    body: form
  }, 90_000);
  const json = await responseJson(response);
  if (!response.ok) throw providerError(response.status, 'OpenAI image edit request failed');
  const item = json?.data?.[0];
  if (!item?.b64_json) throw providerError(502, 'OpenAI image edit returned no binary output');
  return { buffer: Buffer.from(item.b64_json, 'base64'), mimeType: 'image/png', extension: 'png', provider: 'openai' };
}

async function stabilityImage(prompt, imageData = null) {
  const key = process.env.STABILITY_API_KEY;
  if (!key) throw providerError(503, 'Stability image provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const form = new FormData();
  form.append('prompt', prompt);
  if (imageData) {
    const match = imageData.match(/^data:(image\/[a-z0-9.+-]+);base64,(.+)$/i);
    const mime = match?.[1] || 'image/png';
    const ext = mime.includes('jpeg') ? 'jpg' : mime.split('/')[1].split('+')[0];
    form.append('image', new Blob([Buffer.from(match[2], 'base64')], { type: mime }), 'input.' + ext);
    form.append('strength', String(Math.min(Math.max(Number(process.env.STABILITY_IMAGE_STRENGTH || 0.65), 0), 1)));
    const response = await fetchWithTimeout('https://api.stability.ai/v2beta/stable-image/edit', {
      method: 'POST',
      headers: { authorization: 'Bearer ' + key, accept: 'image/*' },
      body: form
    }, 90_000);
    if (!response.ok) throw providerError(response.status, 'Stability image edit request failed');
    return { buffer: await responseBytes(response), mimeType: 'image/png', extension: 'png', provider: 'stability' };
  }
  form.append('output_format', 'png');
  const response = await fetchWithTimeout('https://api.stability.ai/v2beta/stable-image/generate/ultra', {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key, accept: 'image/*' },
    body: form
  }, 90_000);
  if (!response.ok) throw providerError(response.status, 'Stability image request failed');
  return { buffer: await responseBytes(response), mimeType: 'image/png', extension: 'png', provider: 'stability' };
}

async function runwaySubmit(prompt, imageData = null, ratio = '1280:720', duration = 5) {
  const key = process.env.RUNWAYML_API_SECRET;
  if (!key) throw providerError(503, 'Runway provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const payload = {
    model: process.env.RUNWAY_VIDEO_MODEL || 'gen4.5',
    promptText: prompt,
    ratio: videoRatio(ratio),
    duration: Math.min(Math.max(Number(duration) || 5, 2), MAX_VIDEO_SECONDS)
  };
  if (imageData) payload.promptImage = assertImageData(imageData);
  const response = await fetchWithTimeout('https://api.dev.runwayml.com/v1/image_to_video', {
    method: 'POST',
    headers: {
      authorization: 'Bearer ' + key,
      'content-type': 'application/json',
      'X-Runway-Version': '2024-11-06'
    },
    body: JSON.stringify(payload)
  }, 30_000);
  const json = await responseJson(response);
  if (!response.ok || !json?.id) throw providerError(response.status || 502, 'Runway task submission failed');
  return { taskId: String(json.id), provider: 'runway' };
}

async function runwayPoll(taskId) {
  const key = process.env.RUNWAYML_API_SECRET;
  const response = await fetchWithTimeout(
    'https://api.dev.runwayml.com/v1/tasks/' + encodeURIComponent(taskId),
    { headers: { authorization: 'Bearer ' + key, 'X-Runway-Version': '2024-11-06' } },
    20_000
  );
  const json = await responseJson(response);
  if (!response.ok) throw providerError(response.status || 502, 'Runway task lookup failed');
  return json;
}

function assertVideoData(value) {
  if (typeof value !== 'string' || !/^data:video\/(?:mp4|webm|quicktime|x-m4v);base64,[A-Za-z0-9+/=]+$/i.test(value)) {
    throw providerError(400, 'Invalid video input', 'INVALID_MEDIA_VIDEO');
  }
  const comma = value.indexOf(',');
  const bytes = Buffer.byteLength(value.slice(comma + 1), 'base64');
  const max = 18 * 1024 * 1024;
  if (bytes > max) throw providerError(413, 'Video input too large', 'MEDIA_VIDEO_TOO_LARGE');
  return value;
}

async function genericVideoToVideoSubmit(provider, prompt, videoData, ratio, duration) {
  const endpoint = String(process.env['IAC33_' + provider.toUpperCase() + '_VIDEO_TO_VIDEO_ENDPOINT'] || '').trim();
  const statusEndpoint = String(process.env['IAC33_' + provider.toUpperCase() + '_VIDEO_TO_VIDEO_STATUS_ENDPOINT'] || '').trim();
  const key = String(process.env[provider.toUpperCase() + '_API_KEY'] || '').trim();
  if (!endpoint || !statusEndpoint || !key) throw providerError(503, provider + ' video-to-video provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout(endpoint, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key, 'content-type': 'application/json' },
    body: JSON.stringify({
      prompt,
      video: videoData,
      ratio: videoRatio(ratio),
      duration: Math.min(Math.max(Number(duration) || 5, 2), MAX_VIDEO_SECONDS)
    })
  }, 30_000);
  const json = await responseJson(response);
  if (!response.ok || !(json.id || json.jobId || json.taskId)) {
    throw providerError(response.status || 502, provider + ' video-to-video task submission failed');
  }
  return { taskId: String(json.id || json.jobId || json.taskId), provider, statusEndpoint };
}

async function pollGenericVideoToVideo(provider, submit) {
  const key = String(process.env[provider.toUpperCase() + '_API_KEY'] || '').trim();
  const endpoint = String(submit.statusEndpoint || '').trim();
  if (!endpoint || !key) throw providerError(503, provider + ' video-to-video status endpoint not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout(endpoint.replace(/\/$/, '') + '/' + encodeURIComponent(submit.taskId), {
    headers: { authorization: 'Bearer ' + key }
  }, 20_000);
  const json = await responseJson(response);
  if (!response.ok) throw providerError(response.status || 502, provider + ' video-to-video task lookup failed');
  return json;
}

async function pollAndDownloadVideoToVideo(job, submit) {
  const started = Date.now();
  const timeoutMs = Math.min(Math.max(Number(process.env.IAC33_MEDIA_JOB_TIMEOUT_MS || 8 * 60 * 1000), 60_000), 15 * 60 * 1000);
  while (Date.now() - started < timeoutMs) {
    const task = await pollGenericVideoToVideo(submit.provider, submit);
    const status = extractTaskStatus(task);
    job.updatedAt = Date.now();
    if (status === 'SUCCEEDED' || status === 'COMPLETED') {
      const url = extractVideoUrl(task);
      if (!url) throw providerError(502, 'Video-to-video task completed without an output URL');
      const response = await fetchWithTimeout(url, { headers: { accept: 'video/mp4,video/*' } }, 90_000);
      if (!response.ok) throw providerError(response.status, 'Transformed video download failed');
      return saveAsset(await responseBytes(response), 'video/mp4', 'mp4');
    }
    if (status === 'FAILED' || status === 'CANCELED') throw providerError(502, 'Video-to-video task failed');
    await sleep(5_000 + Math.floor(Math.random() * 1_000));
  }
  throw providerError(504, 'Video-to-video job timeout', 'MEDIA_JOB_TIMEOUT');
}

async function generateVideoToVideoJob(prompt, videoData, ratio, duration) {
  const id = crypto.randomUUID();
  const now = Date.now();
  const job = { id, kind: 'VIDEO_TO_VIDEO', status: 'QUEUED', provider: null, createdAt: now, updatedAt: now, assetId: null };
  return enqueueMediaJob(job, async () => {
    let lastError = null;
    const providers = String(process.env.IAC33_VIDEO_TO_VIDEO_PROVIDERS || '')
      .split(',').map(v => v.trim().toLowerCase()).filter(Boolean);
    for (const provider of providers) {
      try {
        const submit = await genericVideoToVideoSubmit(provider, prompt, videoData, ratio, duration);
        job.provider = provider;
        const asset = await pollAndDownloadVideoToVideo(job, submit);
        job.assetId = asset.id;
        job.status = 'SUCCEEDED';
        job.updatedAt = Date.now();
        return;
      } catch (error) {
        lastError = error;
        if (error?.code === 'MEDIA_PROVIDER_UNCONFIGURED') continue;
      }
    }
    job.status = 'FAILED';
    job.errorCode = lastError?.code || 'MEDIA_PROVIDERS_UNAVAILABLE';
    job.updatedAt = Date.now();
    await persistMediaJob(job);
    throw lastError || providerError(503, 'No video-to-video provider available', 'MEDIA_PROVIDERS_UNAVAILABLE');
  });
}

async function genericVideoSubmit(provider, prompt, imageData, ratio, duration) {
  const endpoint = String(process.env['IAC33_' + provider.toUpperCase() + '_ENDPOINT'] || '').trim();
  const key = String(process.env[provider.toUpperCase() + '_API_KEY'] || '').trim();
  if (!endpoint || !key) throw providerError(503, provider + ' provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout(endpoint, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key, 'content-type': 'application/json' },
    body: JSON.stringify({
      prompt,
      image: imageData || undefined,
      ratio: videoRatio(ratio),
      duration: Math.min(Math.max(Number(duration) || 5, 2), MAX_VIDEO_SECONDS)
    })
  }, 30_000);
  const json = await responseJson(response);
  if (!response.ok || !(json.id || json.jobId || json.taskId)) {
    throw providerError(response.status || 502, provider + ' task submission failed');
  }
  return { taskId: String(json.id || json.jobId || json.taskId), provider };
}

async function pollGenericVideo(provider, taskId) {
  const endpoint = String(process.env['IAC33_' + provider.toUpperCase() + '_STATUS_ENDPOINT'] || '').trim();
  const key = String(process.env[provider.toUpperCase() + '_API_KEY'] || '').trim();
  if (!endpoint || !key) throw providerError(503, provider + ' status endpoint not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout(endpoint.replace(/\/$/, '') + '/' + encodeURIComponent(taskId), {
    headers: { authorization: 'Bearer ' + key }
  }, 20_000);
  const json = await responseJson(response);
  if (!response.ok) throw providerError(response.status || 502, provider + ' task lookup failed');
  return json;
}

function extractVideoUrl(task) {
  const output = Array.isArray(task?.output) ? task.output.find(v => typeof v === 'string' && /^https?:\/\//i.test(v)) : null;
  return output || task?.videoUrl || task?.result?.videoUrl || task?.outputUrl || null;
}

function extractTaskStatus(task) {
  return String(task?.status || task?.state || '').toUpperCase();
}

async function pollAndDownloadVideo(job, submit) {
  const started = Date.now();
  const timeoutMs = Math.min(Math.max(Number(process.env.IAC33_MEDIA_JOB_TIMEOUT_MS || 8 * 60 * 1000), 60_000), 15 * 60 * 1000);
  let task = null;
  while (Date.now() - started < timeoutMs) {
    task = submit.provider === 'runway'
      ? await runwayPoll(submit.taskId)
      : await pollGenericVideo(submit.provider, submit.taskId);
    const status = extractTaskStatus(task);
    job.updatedAt = Date.now();
    if (status === 'SUCCEEDED' || status === 'COMPLETED') {
      const url = extractVideoUrl(task);
      if (!url) throw providerError(502, 'Video task completed without an output URL');
      const response = await fetchWithTimeout(url, { headers: { accept: 'video/mp4,video/*' } }, 90_000);
      if (!response.ok) throw providerError(response.status, 'Generated video download failed');
      const buffer = await responseBytes(response);
      return await saveAsset(buffer, 'video/mp4', 'mp4');
    }
    if (status === 'FAILED' || status === 'CANCELED') {
      throw providerError(502, 'Video task failed');
    }
    await sleep(5_000 + Math.floor(Math.random() * 1_000));
  }
  throw providerError(504, 'Video job timeout', 'MEDIA_JOB_TIMEOUT');
}

async function generateVideoJob(kind, prompt, imageData, ratio, duration) {
  const id = crypto.randomUUID();
  const now = Date.now();
  const job = { id, kind, status: 'QUEUED', provider: null, createdAt: now, updatedAt: now, assetId: null };
  return enqueueMediaJob(job, async () => {
    let lastError = null;
    for (const provider of VIDEO_PROVIDERS) {
      try {
        const submit = provider === 'runway'
          ? await runwaySubmit(prompt, imageData, ratio, duration)
          : await genericVideoSubmit(provider, prompt, imageData, ratio, duration);
        job.provider = submit.provider;
        const asset = await pollAndDownloadVideo(job, submit);
        job.assetId = asset.id;
        job.status = 'SUCCEEDED';
        job.updatedAt = Date.now();
        return;
      } catch (error) {
        lastError = error;
        if (error?.code === 'MEDIA_PROVIDER_UNCONFIGURED') continue;
      }
    }
    job.status = 'FAILED';
    job.errorCode = lastError?.code || 'MEDIA_PROVIDERS_UNAVAILABLE';
    job.updatedAt = Date.now();
    await persistMediaJob(job);
    throw lastError || providerError(503, 'No video provider available', 'MEDIA_PROVIDERS_UNAVAILABLE');
  });
}

async function openAiSpeech(text, voice = 'alloy') {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw providerError(503, 'OpenAI TTS provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout('https://api.openai.com/v1/audio/speech', {
    method: 'POST',
    headers: { authorization: 'Bearer ' + key, 'content-type': 'application/json' },
    body: JSON.stringify({
      model: process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts',
      voice: String(voice || process.env.OPENAI_TTS_VOICE || 'alloy'),
      input: text,
      format: 'mp3'
    })
  }, 60_000);
  if (!response.ok) throw providerError(response.status, 'OpenAI TTS request failed');
  return { buffer: await responseBytes(response, 10 * 1024 * 1024), mimeType: 'audio/mpeg', extension: 'mp3', provider: 'openai' };
}

async function elevenLabsSpeech(text, voice = null) {
  const key = process.env.ELEVENLABS_API_KEY;
  if (!key) throw providerError(503, 'ElevenLabs provider not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const voiceId = String(voice || process.env.ELEVENLABS_VOICE_ID || '').trim();
  if (!voiceId) throw providerError(503, 'ElevenLabs voice not configured', 'MEDIA_PROVIDER_UNCONFIGURED');
  const response = await fetchWithTimeout(
    'https://api.elevenlabs.io/v1/text-to-speech/' + encodeURIComponent(voiceId) + '?output_format=mp3_44100_128',
    {
      method: 'POST',
      headers: { 'xi-api-key': key, 'content-type': 'application/json' },
      body: JSON.stringify({ text, model_id: process.env.ELEVENLABS_MODEL_ID || 'eleven_multilingual_v2' })
    },
    60_000
  );
  if (!response.ok) throw providerError(response.status, 'ElevenLabs TTS request failed');
  return { buffer: await responseBytes(response, 10 * 1024 * 1024), mimeType: 'audio/mpeg', extension: 'mp3', provider: 'elevenlabs' };
}

async function withFallback(order, operation) {
  let lastError = null;
  for (const provider of order) {
    try {
      return await operation(provider);
    } catch (error) {
      lastError = error;
      if (error?.code === 'MEDIA_PROVIDER_UNCONFIGURED') continue;
      if (![408, 425, 429, 500, 502, 503, 504].includes(Number(error?.status))) throw error;
    }
  }
  throw lastError || providerError(503, 'No multimedia provider available', 'MEDIA_PROVIDERS_UNAVAILABLE');
}

export async function textToImage(input) {
  const prompt = assertPrompt(input.prompt);
  const result = await withFallback(IMAGE_PROVIDERS, provider => {
    if (provider === 'openai') return openAiImage(prompt);
    if (provider === 'stability') return stabilityImage(prompt);
    throw providerError(503, provider + ' unavailable', 'MEDIA_PROVIDER_UNCONFIGURED');
  });
  const asset = await saveAsset(result.buffer, result.mimeType, result.extension);
  return { assetId: asset.id, mimeType: asset.mimeType, provider: result.provider };
}

export async function imageToImage(input) {
  const prompt = assertPrompt(input.prompt);
  const image = assertImageData(input.image);
  const result = await withFallback(IMAGE_PROVIDERS, provider => {
    if (provider === 'openai') return openAiImageEdit(prompt, image);
    if (provider === 'stability') return stabilityImage(prompt, image);
    throw providerError(503, provider + ' unavailable', 'MEDIA_PROVIDER_UNCONFIGURED');
  });
  const asset = await saveAsset(result.buffer, result.mimeType, result.extension);
  return { assetId: asset.id, mimeType: asset.mimeType, provider: result.provider };
}

export async function textToVideo(input) {
  return generateVideoJob('TEXT_TO_VIDEO', assertPrompt(input.prompt), null, input.ratio, input.duration);
}

export async function imageToVideo(input) {
  return generateVideoJob('IMAGE_TO_VIDEO', assertPrompt(input.prompt), assertImageData(input.image), input.ratio, input.duration);
}

export async function videoToVideo(input) {
  return generateVideoToVideoJob(assertPrompt(input.prompt), assertVideoData(input.video), input.ratio, input.duration);
}

export async function textToSpeech(input) {
  const text = assertPrompt(input.text);
  const result = await withFallback(TTS_PROVIDERS, provider => {
    if (provider === 'openai') return openAiSpeech(text, input.voice);
    if (provider === 'elevenlabs') return elevenLabsSpeech(text, input.voice);
    throw providerError(503, provider + ' unavailable', 'MEDIA_PROVIDER_UNCONFIGURED');
  });
  const asset = await saveAsset(result.buffer, result.mimeType, result.extension);
  return { assetId: asset.id, mimeType: asset.mimeType, provider: result.provider };
}

export async function getJob(id) {
  return jobs.get(id) || await readPersistedJob(id);
}

export async function readAsset(id) {
  const asset = assets.get(id);
  if (asset?.filePath) {
    try {
      const stat = await fs.stat(asset.filePath);
      if (Date.now() - asset.createdAt > ASSET_TTL_MS) {
        await fs.rm(asset.filePath, { force: true });
        assets.delete(id);
        return null;
      }
      return { ...asset, size: stat.size, data: await fs.readFile(asset.filePath) };
    } catch {
      assets.delete(id);
    }
  }
  if (mediaPersistenceEnabled()) {
    const persisted = await readPersistedAsset(id);
    if (persisted) return persisted;
  }
  return null;
}

export function cleanupExpiredMedia() {
  const now = Date.now();
  cleanupExpiredMediaJobs(now);
  for (const [id, asset] of assets) {
    if (now - asset.createdAt > ASSET_TTL_MS) {
      fs.rm(asset.filePath, { force: true }).catch(() => {});
      assets.delete(id);
    }
  }
}

setInterval(cleanupExpiredMedia, 15 * 60 * 1000).unref();

export { assetUrl, jobPublic };


void markInterruptedMediaJobs().catch((error) => {
  if (mediaPersistenceEnabled()) console.error('IAC33 media persistence startup check failed:', error?.message || error);
});
