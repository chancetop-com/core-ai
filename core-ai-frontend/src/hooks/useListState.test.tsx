import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { clearListState, useListState } from './useListState';

function Probe() {
  const [value, setValue] = useListState('probe.value', 'initial');
  return <button onClick={() => setValue(current => `${current}!`)}>{value}</button>;
}

describe('useListState', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('restores the last value when the component mounts again', () => {
    const first = render(<Probe />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('button').textContent).toBe('initial!');
    first.unmount();

    render(<Probe />);
    expect(screen.getByRole('button').textContent).toBe('initial!');
  });

  it('uses the default when the stored value is not valid json', () => {
    window.sessionStorage.setItem('core-ai:list-state:probe.value', '{broken');
    render(<Probe />);
    expect(screen.getByRole('button').textContent).toBe('initial');
  });

  it('clears every persisted list value', () => {
    const first = render(<Probe />);
    fireEvent.click(screen.getByRole('button'));
    first.unmount();

    clearListState();
    render(<Probe />);
    expect(screen.getByRole('button').textContent).toBe('initial');
  });
});
