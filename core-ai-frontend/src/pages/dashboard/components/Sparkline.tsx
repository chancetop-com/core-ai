import type { TrendPoint } from '../../../api/client';

interface SparklineProps {
  data: TrendPoint[];
  width?: number;
  height?: number;
  color?: string;
  dataKey?: keyof TrendPoint;
}

export default function Sparkline({ data, width = 80, height = 24, color = '#6366f1', dataKey = 'inputTokens' }: SparklineProps) {
  const values = data.map(d => Number(d[dataKey]) || 0);
  if (values.length < 2) return <div style={{ width, height }} />;

  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;

  const points = values.map((v, i) => {
    const x = (i / (values.length - 1)) * (width - 2) + 1;
    const y = height - 2 - ((v - min) / range) * (height - 4);
    return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
  }).join(' ');

  return (
    <svg width={width} height={height} className="inline-block align-middle">
      <path d={points} fill="none" stroke={color} strokeWidth={1.5}
        strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
