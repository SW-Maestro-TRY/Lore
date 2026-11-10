/** Pure shared routing policy: no browser, React, or provider side effects. */
export type ZzalLocale = 'ko' | 'en' | 'ja';
export function supportedZzalLocale(value: string | null | undefined): ZzalLocale | null {
  return value === 'ko' || value === 'en' || value === 'ja' ? value : null;
}
export function browserZzalLocale(acceptLanguage: string | null | undefined): ZzalLocale {
  const choices = (acceptLanguage ?? '').split(',').map((part, index) => {
    const [tag, ...params] = part.trim().split(';');
    const quality = params.find(p => p.trim().toLowerCase().startsWith('q='));
    const q = quality == null ? 1 : Number(quality.trim().slice(2));
    return { locale: supportedZzalLocale(tag.toLowerCase().split('-')[0]), q, index };
  }).filter(x => x.locale && Number.isFinite(x.q) && x.q > 0 && x.q <= 1)
    .sort((a, b) => b.q - a.q || a.index - b.index);
  return choices[0]?.locale ?? 'en';
}
export function resolveZzalLocale(query: string | null | undefined, saved: string | null | undefined, acceptLanguage: string | null | undefined): ZzalLocale {
  return supportedZzalLocale(query) ?? supportedZzalLocale(saved) ?? browserZzalLocale(acceptLanguage);
}
