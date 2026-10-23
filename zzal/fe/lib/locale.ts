'use client';
import { createContext, createElement, useContext, useEffect, useState, type ReactNode } from 'react';
import { resolveZzalLocale, type ZzalLocale } from './localeRouting';
export type { ZzalLocale } from './localeRouting';
const InitialLocale=createContext<ZzalLocale>('en');
export function ZzalLocaleProvider({initialLocale,children}:{initialLocale:ZzalLocale;children:ReactNode}) { return createElement(InitialLocale.Provider,{value:initialLocale},children); }
/** Shared language setting only; authored text and saved turns are never translated. */
export function readZzalLocale(): ZzalLocale {
  if (typeof document === 'undefined') return 'en';
  const saved = document.cookie.split(';').map(v=>v.trim()).find(v=>v.startsWith('lore_locale='))?.slice('lore_locale='.length);
  return resolveZzalLocale(new URL(window.location.href).searchParams.get('lang'),saved,navigator.languages?.join(',')||navigator.language);
}
export function setZzalLocale(locale: ZzalLocale): void {
  document.cookie=`lore_locale=${locale}; Path=/; Max-Age=31536000; SameSite=Lax`;
  const url=new URL(window.location.href);url.searchParams.set('lang',locale);
  window.history.replaceState(window.history.state,'',url);
  document.documentElement.lang=locale;
  window.dispatchEvent(new Event('zzal-locale-change'));
}
export function useZzalLocale(): ZzalLocale {
  const initial=useContext(InitialLocale);
  const [locale, setLocale] = useState<ZzalLocale>(initial);
  useEffect(() => {
    const sync = () => {const next=readZzalLocale();setLocale(next);document.documentElement.lang=next;};
    sync(); window.addEventListener('pageshow',sync);window.addEventListener('popstate',sync);window.addEventListener('zzal-locale-change',sync);
    return()=>{window.removeEventListener('pageshow',sync);window.removeEventListener('popstate',sync);window.removeEventListener('zzal-locale-change',sync);};
  }, []);
  return locale;
}
