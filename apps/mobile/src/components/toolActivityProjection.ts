import type { StructuredBlock } from './StructuredContent';
import type { PersistedSessionEventV3 } from '../state/types';

const MAX_SUMMARY_CHARACTERS = 96;

/**
 * A short, display-only summary of one tool call's arguments: the path for
 * the file tools, compact `key: value` pairs otherwise, everything truncated.
 * Raw text that does not parse (a call still streaming) is shown as-is,
 * truncated, rather than hidden — a card that says which file a tool touched
 * is the difference between "the agent did something" and "the agent read
 * README.md".
 */
export function toolArgumentsSummary(raw: string): string {
  const text = raw.trim();
  if (text.length === 0) return '';
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return truncate(text);
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return truncate(text);
  }
  const record = parsed as Record<string, unknown>;
  const parts: string[] = [];
  if (typeof record.path === 'string') {
    parts.push(record.path.length === 0 ? '/' : record.path);
  }
  for (const [key, value] of Object.entries(record)) {
    if (key === 'path') continue;
    if (key === 'content' && typeof value === 'string') {
      parts.push(`${value.length} chars`);
      continue;
    }
    if (value === null || typeof value === 'object') continue;
    parts.push(`${key}: ${String(value)}`);
  }
  return truncate(parts.join(' · '));
}

function truncate(text: string): string {
  const flattened = text.replace(/\s+/g, ' ');
  return flattened.length <= MAX_SUMMARY_CHARACTERS
    ? flattened
    : `${flattened.slice(0, MAX_SUMMARY_CHARACTERS - 1)}…`;
}

export function projectToolActivity(
  events: readonly PersistedSessionEventV3[],
  attemptId: string,
  argumentsByCall?: ReadonlyMap<string, string>,
): StructuredBlock[] {
  const ordered = events.filter(event => event.attempt_id === attemptId).slice().sort((a, b) => a.seq - b.seq);
  const calls = new Map<string, { eventId: string; name: string; createdAt: string; roundIndex: number | null }>();
  const blocks: StructuredBlock[] = [];
  for (const event of ordered) {
    if (event.kind === 'tool_call' && event.call_id !== null) {
      const name = event.safe_summary_key?.startsWith('agent.') ? event.safe_summary_key.slice(6) : 'tool';
      const key = `${event.round_index ?? 'null'}:${event.call_id}`;
      calls.set(key, { eventId: event.event_id, name, createdAt: event.created_at, roundIndex: event.round_index });
      // Persisted events carry only the arguments' digest; the plaintext
      // arguments come from the round's own streamed preview when the round
      // is still in memory, and are display-only either way.
      blocks.push({ id: event.event_id, type: 'tool-call', name, arguments: argumentsByCall?.get(key) ?? '', status: event.status === 'running' ? 'running' : event.status === 'cancelled' ? 'cancelled' : event.status === 'failed' || event.status === 'denied' ? 'error' : 'pending', ...failureDetails(event) });
    } else if (event.kind === 'tool_result' && event.call_id !== null) {
      const call = calls.get(`${event.round_index ?? 'null'}:${event.call_id}`); if (!call) continue;
      const failed = event.status === 'failed' || event.status === 'denied';
      const elapsed = duration(call.createdAt, event.created_at);
      const prior = blocks.find(block => block.id === call.eventId); if (prior?.type === 'tool-call') { prior.status = event.status === 'ok' ? 'success' : event.status === 'cancelled' ? 'cancelled' : failed ? 'error' : 'unknown'; Object.assign(prior, failureDetails(event)); if (elapsed !== undefined) prior.durationMs = elapsed; }
    } else if (event.kind === 'cancel' || (event.kind === 'terminal' && event.status !== 'ok')) {
      for (const block of blocks) if (block.type === 'tool-call' && (block.status === 'running' || block.status === 'pending') && (event.call_id !== null ? block.id === calls.get(`${event.round_index ?? 'null'}:${event.call_id}`)?.eventId : event.round_index === null || [...calls.values()].some(call => call.eventId === block.id && call.roundIndex === event.round_index))) {
        block.status = event.kind === 'cancel' || event.status === 'cancelled' ? 'cancelled' : 'unknown';
        // A round/attempt failure does not establish an individual tool's cause.
        if (event.call_id !== null) Object.assign(block, failureDetails(event));
      }
    }
  }
  return blocks;
}
function failureDetails(event: PersistedSessionEventV3) {
  return {
    failureCode: event.status === 'ok' ? undefined : event.failure_code ?? undefined,
    denied: event.status === 'denied' || undefined,
  };
}
function duration(start: string, end: string): number | undefined { const a = Date.parse(start); const b = Date.parse(end); return Number.isFinite(a) && Number.isFinite(b) && b >= a ? b - a : undefined; }
