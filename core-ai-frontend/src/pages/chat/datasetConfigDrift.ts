export interface DatasetBinding {
  dataset_id: string;
  permission?: string;
  is_output?: boolean;
}

function bindingKey(binding: DatasetBinding): string {
  return `${binding.dataset_id}:${binding.permission ?? ''}:${binding.is_output === true ? '1' : '0'}`;
}

/**
 * True when the bindings a session captured at creation no longer match the agent's current ones. The session keeps
 * its captured tools for its whole life, so a mismatch means the user has to open a new chat to see the change.
 */
export function datasetBindingsDiffer(
  snapshot: DatasetBinding[] | undefined,
  current: DatasetBinding[] | undefined,
): boolean {
  const left = snapshot ?? [];
  const right = current ?? [];
  if (left.length === 0 && right.length === 0) return false;
  if (left.length !== right.length) return true;
  const leftKeys = left.map(bindingKey).sort();
  const rightKeys = right.map(bindingKey).sort();
  return leftKeys.some((key, index) => key !== rightKeys[index]);
}
