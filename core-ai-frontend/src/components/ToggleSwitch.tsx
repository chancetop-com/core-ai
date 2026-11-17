interface ToggleSwitchProps {
  checked: boolean;
  onChange?: (checked: boolean) => void;
  disabled?: boolean;
  label: string;
}

export default function ToggleSwitch({ checked, onChange, disabled = false, label }: ToggleSwitchProps) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label}
      disabled={disabled}
      onClick={() => onChange?.(!checked)}
      className="relative inline-flex items-center w-10 h-5 rounded-full transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-40"
      style={{ background: checked ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
      <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
        style={{ transform: checked ? 'translateX(22px)' : 'translateX(2px)' }} />
    </button>
  );
}
