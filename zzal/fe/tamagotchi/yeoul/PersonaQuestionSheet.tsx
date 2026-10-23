import type {ReactNode,Ref} from 'react';
import {C,gap,radius,ink} from './ui';
export const PERSONA_LAYOUT_STYLE = `
.pe-root{position:relative;overflow:hidden;--pe-stage:190px}
.pe-stage{flex:0 0 var(--pe-stage);min-height:0}
.pe-sheet{position:relative}
.pe-keyboard-done{position:absolute;right:16px;top:-49px;z-index:4;height:44px;min-width:52px;border:1px solid #D8C9BD;border-radius:999px;background:#FFFDF8;color:#9C4232;font:700 18px Gaegu}

/* Questions rest at the bottom too; long chip lists scroll inside the sheet.
   Grow the egg only inside the available stage; the keyboard viewport path keeps its existing 200px egg. */
.pe-root:not([data-q="done"]):not([data-kb="1"]) .pe-stage{flex:1 0 230px;min-height:230px;container-type:size}
@supports(height:1cqh){
.pe-root:not([data-q="done"]):not([data-kb="1"]) [data-part="pe-egg"]{--question-egg:min(340px,calc(100vw - 64px),max(200px,calc(100cqh - 30px)));width:var(--question-egg)!important;height:var(--question-egg)!important;min-height:0!important;max-height:none!important;margin-top:0!important;margin-bottom:0!important}
}
.pe-root:not([data-q="done"]):not([data-kb="1"]) .pe-sheet{flex:0 1 auto!important;margin-top:auto!important}
.pe-root:not([data-q="done"]):not([data-kb="1"]) .pe-scroll{flex:0 1 auto!important}

/* Completed waiting: preserve the approved larger egg. */
.pe-root[data-waiting="true"] .pe-stage{flex:1 1 auto;min-height:0}
.pe-root[data-waiting="true"] [data-part="pe-egg"]{--waiting-egg:min(340px,calc(100vw - 64px),max(240px,42vh));width:var(--waiting-egg)!important;height:var(--waiting-egg)!important;min-height:0!important;max-height:none!important;margin-top:0!important;margin-bottom:0!important}
.pe-root[data-waiting="true"] .pe-sheet{flex:0 0 auto!important;margin-top:auto!important}
.pe-root[data-waiting="true"] .pe-scroll{flex:0 0 auto!important}

.pe-chip{ position:relative; min-width:44px; transition:opacity .15s; }
.pe-chip[data-flying="1"]{ opacity:.35; }
.pe-chip:disabled:not([aria-pressed="true"]){ opacity:.45; }
.pe-scroll{ -webkit-overflow-scrolling:touch; overscroll-behavior:contain; }
.pe-text::placeholder{ font-size:14px; line-height:1.4; color:rgba(74,64,56,.42); }
@media (prefers-reduced-motion: reduce){
  .pe-eggwrap{ animation:none!important; }
}
`;
export function PersonaQuestionSheet({children}:{children:ReactNode}) {return <div className="pe-sheet" style={{flex:'1 1 auto',minHeight:0,display:'flex',flexDirection:'column',background:C.paper,borderTopLeftRadius:radius.xl,borderTopRightRadius:radius.xl,boxShadow:`0 -6px 20px ${ink(.06)}`,marginTop:gap.sm}}>{children}</div>}
export function PersonaQuestionPane({children,paneRef}:{children:ReactNode;paneRef:Ref<HTMLDivElement>}) {return <div ref={paneRef} className="pe-scroll" style={{flex:'1 1 auto',minHeight:0,overflow:'auto',padding:'16px 20px 8px',display:'flex',flexDirection:'column',gap:gap.md}}>{children}</div>}
export function PersonaQuestionFooter({children}:{children:ReactNode}) {return <div className="pe-footer" style={{flex:'none',display:'flex',flexDirection:'column',gap:2,padding:'8px 16px',paddingBottom:'max(14px, env(safe-area-inset-bottom))'}}>{children}</div>}
