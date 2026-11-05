// Message bubble timestamps. Kept pure so the label rules are testable without the DOM.

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export interface MessageTimeLabel {
  label: string;
  title: string;
}

/**
 * Today reads as "14:32"; older messages carry their date so a session that spans days stays
 * readable, while the tooltip always holds the full local timestamp.
 */
export function messageTimeLabel(ts: number, now: Date = new Date()): MessageTimeLabel | null {
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return null;
  const sameDay = (left: Date, right: Date): boolean => left.getFullYear() === right.getFullYear()
    && left.getMonth() === right.getMonth() && left.getDate() === right.getDate();
  const pad = (value: number): string => String(value).padStart(2, '0');
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  let label = clock;
  if (!sameDay(date, now)) {
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    const day = `${MONTHS[date.getMonth()]} ${date.getDate()}`;
    if (sameDay(date, yesterday)) {
      label = `Yesterday ${clock}`;
    } else if (date.getFullYear() === now.getFullYear()) {
      label = `${day}, ${clock}`;
    } else {
      label = `${day}, ${date.getFullYear()}, ${clock}`;
    }
  }
  return { label, title: date.toLocaleString() };
}
