// 올리기 폼 — 이름(12자)·설정(선택)·동의·데려오기(정본 §15 2·3번).
//
// ★ 동의문에 **"올린 그림은 학습에 쓰지 않습니다"** 보증 문장이 반드시 있다(자캐 커뮤니티 규범 처방 3 — "자캐를 학습시켜 드립니다" 로
//   절대 가지 않는다). 동의는 판번호(v1)로 저장된다는 전제(백엔드 가입 동의와 같은 결).
// 그림 드롭 영역은 Scrapbook 이 그린다(폰·PC 배치가 달라서). 여기는 글자 칸과 버튼만.
'use client';

import type { CSSProperties } from 'react';
import { NAME_MAX_CHARS } from '../rules';
import type { Tamagotchi } from '../useTamagotchi';
import { EDGE, GAEGU, INK, PEN, RED, SUB, smallTag } from './theme';

/** 동의문. 판번호를 올리면 백엔드 동의 기록도 같이 올린다. */
export const UPLOAD_CONSENT_VERSION = 1;
export const UPLOAD_CONSENT_LINE = '제가 그린 그림이 맞습니다';
export const NO_TRAINING_LINE = '올린 그림은 학습에 쓰지 않습니다. 이 아이를 움직이는 데만 씁니다';

export interface UploadFormProps {
  tama: Tamagotchi;
  compact?: boolean;
  onSubmit: () => void;
}

export default function UploadForm({ tama, compact = false, onSubmit }: UploadFormProps) {
  const { state: s, derived, form } = tama;
  const canSubmit = derived.canSubmit && !derived.creating;
  const nameLen = Array.from(s.form.name).length;

  const input: CSSProperties = { flex: 1, minWidth: 0, minHeight: 52, padding: '0 14px', border: 'none', borderBottom: '2px solid #D6CBAE', borderRadius: 0, background: 'rgba(255,255,255,.5)', color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 17 };
  const textarea: CSSProperties = { padding: '12px 14px', border: 'none', borderBottom: '2px solid #D6CBAE', background: 'rgba(255,255,255,.5)', color: INK, fontFamily: GAEGU, fontSize: 16, lineHeight: 1.7, resize: 'none' };
  const checkRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 11, minHeight: 56, padding: '0 14px', border: '1px dashed #D6CBAE', borderRadius: 3, background: 'rgba(255,255,255,.45)', cursor: 'pointer' };
  const fieldLabel: CSSProperties = { fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: '#5C5445' };
  const submitStyle: CSSProperties = {
    minHeight: 58, border: '1px solid ' + (canSubmit ? '#2F2A22' : '#DCD2B8'), borderRadius: 3,
    background: canSubmit ? INK : 'rgba(230,224,206,.8)', color: canSubmit ? '#FFF8EC' : '#A79C82',
    fontFamily: GAEGU, fontWeight: 700, fontSize: 17, cursor: canSubmit ? 'pointer' : 'default',
    boxShadow: canSubmit ? '2px 3px 0 rgba(58,53,43,.2)' : 'none', transition: 'all .22s ease',
  };

  return (
    <div data-part="upload-form" style={{ display: 'flex', flexDirection: 'column', gap: compact ? 12 : 17 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {!compact && <span style={fieldLabel}>이름 <span style={{ fontFamily: PEN, fontSize: 14, color: nameLen > NAME_MAX_CHARS ? RED : '#A79C82' }}>{nameLen}/{NAME_MAX_CHARS}</span></span>}
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            value={s.form.name}
            onChange={(e) => form.patchForm({ name: Array.from(e.target.value).slice(0, NAME_MAX_CHARS).join('') })}
            maxLength={NAME_MAX_CHARS}
            placeholder={compact ? `이름 (${NAME_MAX_CHARS}자)` : '아이의 이름'}
            aria-label="아이의 이름"
            data-field="name"
            style={input}
          />
          <button onClick={form.randomName} data-action="random-name" style={smallTag}>랜덤</button>
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {!compact && <span style={fieldLabel}>이 아이에 대해 하고 싶은 말 (선택)</span>}
        <textarea
          value={s.form.note}
          onChange={(e) => form.patchForm({ note: e.target.value.slice(0, 200) })}
          rows={compact ? 2 : 3}
          maxLength={200}
          placeholder={compact ? '하고 싶은 말 (선택)' : '조용하지만 고집이 세요'}
          data-field="note"
          style={textarea}
        />
      </div>
      <label style={checkRow} data-consent={UPLOAD_CONSENT_VERSION}>
        <input type="checkbox" checked={s.form.agree} onChange={(e) => form.patchForm({ agree: e.target.checked })} data-field="agree" style={{ width: 20, height: 20, accentColor: RED, flex: '0 0 auto' }} />
        <span style={{ fontSize: 14, color: '#4A4438' }}>{UPLOAD_CONSENT_LINE}</span>
      </label>
      <button onClick={onSubmit} disabled={!canSubmit} data-action="submit-upload" style={submitStyle}>
        {derived.creating ? '데려오는 중…' : '알로 데려오기'}
      </button>
      <p data-part="no-training" style={{ margin: 0, fontFamily: PEN, fontSize: 16, color: SUB, lineHeight: 1.4 }}>
        {NO_TRAINING_LINE}
        <br />
        <span style={{ fontSize: 14, color: '#A79C82' }}>그림은 그대로 씁니다. 손대지 않아요 · <span style={{ borderBottom: '1px dashed ' + EDGE }}>동의 v{UPLOAD_CONSENT_VERSION}</span></span>
      </p>
    </div>
  );
}
