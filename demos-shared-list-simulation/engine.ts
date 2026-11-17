// Re-exports the exact mergedoc.js the .xdc ships (same file, unmodified).
import './webxdc/mergedoc.js';

declare global {
  interface Window { MergeDoc: any; MERGE_DELETED: string; MERGE_SKEW_WINDOW_MS: number }
}
export const MergeDoc = (window as any).MergeDoc;
export const MERGE_DELETED = (window as any).MERGE_DELETED;
