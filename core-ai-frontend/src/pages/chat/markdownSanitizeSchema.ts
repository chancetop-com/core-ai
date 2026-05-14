import { defaultSchema } from 'rehype-sanitize';
import type { Schema } from 'hast-util-sanitize';

// SVG tag whitelist. foreignObject deliberately excluded — it can embed arbitrary HTML
// (including script) inside SVG and is the main XSS vector for SVG payloads.
const svgTags = [
    'svg', 'g', 'defs', 'title', 'desc', 'use',
    'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon',
    'text', 'tspan', 'textPath',
    'linearGradient', 'radialGradient', 'stop', 'pattern', 'mask', 'clipPath',
    'marker', 'symbol',
];

// HAST property names are camelCase; rehype-raw normalizes parsed kebab-case (e.g. stroke-width)
// to camelCase before sanitize sees them.
const svgAttrs = [
    'viewBox', 'xmlns', 'preserveAspectRatio', 'transform',
    'fill', 'stroke', 'strokeWidth', 'strokeDasharray', 'strokeLinecap', 'strokeLinejoin',
    'opacity', 'fillOpacity', 'strokeOpacity',
    'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry',
    'width', 'height', 'd', 'points',
    'fontFamily', 'fontSize', 'fontWeight', 'textAnchor', 'dominantBaseline', 'dx', 'dy',
    'offset', 'stopColor', 'stopOpacity', 'gradientUnits', 'gradientTransform',
    'href', 'xLinkHref',
];

// Sanitize schema for LLM-emitted markdown that may contain inline SVG / styled divs.
// Built on top of rehype-sanitize defaultSchema:
//   - keeps the default protocol whitelist (http/https/mailto, etc.) — javascript: URLs are stripped
//   - keeps the default "no on* event handler" rule (any attr not listed is dropped)
//   - extends tag whitelist with SVG primitives
//   - extends global attribute whitelist with className and style (needed for LLM-generated cards)
export const chatSanitizeSchema: Schema = {
    ...defaultSchema,
    tagNames: [...(defaultSchema.tagNames ?? []), ...svgTags],
    attributes: {
        ...defaultSchema.attributes,
        '*': [...(defaultSchema.attributes?.['*'] ?? []), 'className', 'style'],
        ...Object.fromEntries(svgTags.map(t => [t, svgAttrs])),
    },
};
