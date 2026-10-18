'use client';
import { createContext, createElement, useContext, useEffect, useState, type ReactNode } from 'react';
export type ZzalLocale = 'ko' | 'en' | 'ja';
const InitialLocale=createContext<ZzalLocale>('ko');
export function ZzalLocaleProvider({initialLocale,children}:{initialLocale:ZzalLocale;children:ReactNode}) { return createElement(InitialLocale.Provider,{value:initialLocale},children); }
/** Same locale cookie as the application middleware; never translates authored text. */
export function readZzalLocale(): ZzalLocale {
  if (typeof document === 'undefined') return 'ko';
  const value = document.cookie.split('; ').find(v => v.startsWith('lore_locale='))?.split('=')[1];
  return value === 'en' || value === 'ja' ? value : 'ko';
}
export function useZzalLocale(): ZzalLocale {
  const initial=useContext(InitialLocale);
  const [locale, setLocale] = useState<ZzalLocale>(initial);
  useEffect(() => {
    const sync = () => setLocale(readZzalLocale());
    sync(); window.addEventListener('pageshow', sync); window.addEventListener('zzal-locale-change', sync);
    return () => { window.removeEventListener('pageshow', sync); window.removeEventListener('zzal-locale-change', sync); };
  }, []);
  return locale;
}
