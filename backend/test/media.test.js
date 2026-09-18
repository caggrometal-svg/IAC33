import test from 'node:test';
import assert from 'node:assert/strict';
import { server } from '../src/server.js';

async function withServer(fn) {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  try {
    await fn(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

test('multimedia routes reject malformed prompts without provider calls', async () => {
  await withServer(async (base) => {
    const response = await fetch(base + '/v1/ai/text-to-image', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ prompt: '' })
    });
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error, 'INVALID_MEDIA_PROMPT');
  });
});

test('text-to-image stores provider binary and returns an asset URL', async () => {
  const originalFetch = globalThis.fetch;
  const oldKey = process.env.OPENAI_API_KEY;
  const oldProviders = process.env.IAC33_IMAGE_PROVIDERS;
  process.env.OPENAI_API_KEY = 'test-key';
  process.env.IAC33_IMAGE_PROVIDERS = 'openai';
  try {
    await withServer(async (base) => {
      globalThis.fetch = async (url, options) => {
        if (String(url).startsWith(base)) return originalFetch(url, options);
        assert.equal(String(url), 'https://api.openai.com/v1/images/generations');
        return new Response(
          JSON.stringify({ data: [{ b64_json: Buffer.from('PNG').toString('base64') }] }),
          { status: 200, headers: { 'content-type': 'application/json' } }
        );
      };
      const response = await originalFetch(base + '/v1/ai/text-to-image', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ prompt: 'a test image' })
      });
      assert.equal(response.status, 200);
      const json = await response.json();
      assert.equal(json.ok, true);
      assert.match(json.assetUrl, /^\/v1\/ai\/media\/assets\//);

      const asset = await originalFetch(base + json.assetUrl);
      assert.equal(asset.status, 200);
      assert.equal(asset.headers.get('content-type'), 'image/png');
      assert.equal(Buffer.from(await asset.arrayBuffer()).toString(), 'PNG');
    });
  } finally {
    globalThis.fetch = originalFetch;
    if (oldKey === undefined) delete process.env.OPENAI_API_KEY; else process.env.OPENAI_API_KEY = oldKey;
    if (oldProviders === undefined) delete process.env.IAC33_IMAGE_PROVIDERS; else process.env.IAC33_IMAGE_PROVIDERS = oldProviders;
  }
});

test('image-to-image rejects oversized base64 input', async () => {
  await withServer(async (base) => {
    const huge = 'data:image/png;base64,' + Buffer.alloc(12 * 1024 * 1024 + 1).toString('base64');
    const response = await fetch(base + '/v1/ai/image-to-image', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ prompt: 'edit', image: huge })
    });
    assert.equal(response.status, 413);
  });
});

test('text-to-speech stores generated audio', async () => {
  const originalFetch = globalThis.fetch;
  const oldKey = process.env.OPENAI_API_KEY;
  const oldProviders = process.env.IAC33_TTS_PROVIDERS;
  process.env.OPENAI_API_KEY = 'test-key';
  process.env.IAC33_TTS_PROVIDERS = 'openai';
  try {
    await withServer(async (base) => {
      globalThis.fetch = async (url, options) => {
        if (String(url).startsWith(base)) return originalFetch(url, options);
        assert.equal(String(url), 'https://api.openai.com/v1/audio/speech');
        return new Response(new Uint8Array([73, 68, 51]), {
          status: 200,
          headers: { 'content-type': 'audio/mpeg' }
        });
      };
      const response = await originalFetch(base + '/v1/ai/text-to-speech', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ text: 'hola IAC33' })
      });
      assert.equal(response.status, 200);
      const json = await response.json();
      const asset = await originalFetch(base + json.assetUrl);
      assert.equal(asset.status, 200);
      assert.equal(Buffer.from(await asset.arrayBuffer()).toString(), 'ID3');
    });
  } finally {
    globalThis.fetch = originalFetch;
    if (oldKey === undefined) delete process.env.OPENAI_API_KEY; else process.env.OPENAI_API_KEY = oldKey;
    if (oldProviders === undefined) delete process.env.IAC33_TTS_PROVIDERS; else process.env.IAC33_TTS_PROVIDERS = oldProviders;
  }
});

test('text-to-video creates an asynchronous Runway-compatible job', async () => {
  const originalFetch = globalThis.fetch;
  const oldKey = process.env.RUNWAYML_API_SECRET;
  const oldProviders = process.env.IAC33_VIDEO_PROVIDERS;
  process.env.RUNWAYML_API_SECRET = 'test-key';
  process.env.IAC33_VIDEO_PROVIDERS = 'runway';
  try {
    await withServer(async (base) => {
      globalThis.fetch = async (url, options) => {
        if (String(url).startsWith(base)) return originalFetch(url, options);
        const target = String(url);
        if (target === 'https://api.dev.runwayml.com/v1/image_to_video') {
          return new Response(JSON.stringify({ id: 'task-1' }), {
            status: 200,
            headers: { 'content-type': 'application/json' }
          });
        }
        if (target === 'https://api.dev.runwayml.com/v1/tasks/task-1') {
          return new Response(JSON.stringify({
            id: 'task-1',
            status: 'SUCCEEDED',
            output: ['https://cdn.example.test/iac33.mp4']
          }), {
            status: 200,
            headers: { 'content-type': 'application/json' }
          });
        }
        if (target === 'https://cdn.example.test/iac33.mp4') {
          return new Response(new Uint8Array([0, 1, 2, 3]), {
            status: 200,
            headers: { 'content-type': 'video/mp4' }
          });
        }
        throw new Error('Unexpected provider URL ' + target);
      };

      const response = await originalFetch(base + '/v1/ai/text-to-video', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ prompt: 'test video', duration: 2 })
      });
      assert.equal(response.status, 202);
      const initial = await response.json();
      assert.equal(initial.ok, true);
      assert.ok(initial.jobId);

      let job;
      for (let i = 0; i < 20; i += 1) {
        await new Promise(r => setTimeout(r, 50));
        const result = await originalFetch(base + '/v1/ai/media/jobs/' + initial.jobId);
        job = await result.json();
        if (job.status === 'SUCCEEDED') break;
      }
      assert.equal(job.status, 'SUCCEEDED');
      const file = await originalFetch(base + job.assetUrl);
      assert.equal(file.status, 200);
      assert.equal(Buffer.from(await file.arrayBuffer()).length, 4);
    });
  } finally {
    globalThis.fetch = originalFetch;
    if (oldKey === undefined) delete process.env.RUNWAYML_API_SECRET; else process.env.RUNWAYML_API_SECRET = oldKey;
    if (oldProviders === undefined) delete process.env.IAC33_VIDEO_PROVIDERS; else process.env.IAC33_VIDEO_PROVIDERS = oldProviders;
  }
});
