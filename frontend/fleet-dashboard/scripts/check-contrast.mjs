import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const stylesheet = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8');
const colors = new Map(
  [...stylesheet.matchAll(/(--color-[a-z-]+):\s*(#[\da-f]{6});/gi)].map((match) => [
    match[1],
    match[2],
  ]),
);

function luminance(hex) {
  const channels = [1, 3, 5].map((offset) => {
    const value = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrast(first, second) {
  const [lighter, darker] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (lighter + 0.05) / (darker + 0.05);
}

assert.equal(contrast('#000000', '#ffffff'), 21);
for (const foreground of ['text', 'link', 'error', 'control-border', 'focus']) {
  const minimum = ['control-border', 'focus'].includes(foreground) ? 3 : 4.5;
  const backgrounds = ['background', 'surface'];
  if (foreground === 'text') backgrounds.push('muted-surface');
  for (const background of backgrounds) {
    const first = colors.get(`--color-${foreground}`);
    const second = colors.get(`--color-${background}`);
    assert.ok(first && second, `Token colore mancante: ${foreground}/${background}`);
    const ratio = contrast(first, second);
    assert.ok(
      ratio >= minimum,
      `${foreground}/${background}: ${ratio.toFixed(2)}:1, richiesto ${minimum}:1`,
    );
    console.log(`${foreground}/${background}: ${ratio.toFixed(2)}:1 (minimo ${minimum}:1)`);
  }
}
