import { pipeline } from '@huggingface/transformers';

let extractor;

self.onmessage = async ({ data }) => {
  if (data.type !== 'embed') return;
  try {
    if (!extractor) {
      extractor = await pipeline('feature-extraction', 'Xenova/all-MiniLM-L6-v2', {
        progress_callback: progress => self.postMessage({ type: 'model-progress', jobId: data.jobId, progress }),
      });
    }
    const output = await extractor(data.texts, { pooling: 'mean', normalize: true });
    self.postMessage({ type: 'embedded', jobId: data.jobId, vectors: output.tolist() });
  } catch (error) {
    self.postMessage({ type: 'error', jobId: data.jobId, message: error?.message || 'Embedding failed' });
  }
};
