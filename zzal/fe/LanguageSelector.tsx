'use client';
import { useId } from 'react';
import { setZzalLocale, useZzalLocale, type ZzalLocale } from './lib/locale';
import './language-selector.css';
export default function LanguageSelector(){
 const locale=useZzalLocale(),id=useId();
 return <label className="zzal-language-selector" htmlFor={id} data-zzal-language-selector>
  <span className="zzal-language-caption">{locale==='ko'?'화면 언어':locale==='ja'?'表示言語':'Interface language'}</span>
  <select id={id} value={locale} onChange={e=>setZzalLocale(e.target.value as ZzalLocale)}>
   <option value="ko">한국어</option><option value="en">English</option><option value="ja">日本語</option>
  </select><span className="zzal-language-chevron" aria-hidden="true">▾</span>
 </label>;
}
