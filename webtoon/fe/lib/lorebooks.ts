import type {LoreBook} from "./api";
export function attachedBooks(value?:LoreBook|null):LoreBook[]{return value?.books??(value?[value]:[]);}
export function packBooks(books:LoreBook[]):LoreBook|null {
 if(!books.length)return null;
 if(books.length===1)return books[0];
 return {source:"collection",name:books.map(b=>b.name).join(" · "),confirmed:books.every(b=>b.confirmed),books,
  fixed_settings:books.map(b=>b.fixed_settings.trim()?`[${b.name}]\n${b.fixed_settings}`:"").filter(Boolean).join("\n\n"),
  progress_state:books.map(b=>b.progress_state.trim()?`[${b.name}]\n${b.progress_state}`:"").filter(Boolean).join("\n\n"),
  entries:books.flatMap(b=>b.entries)};
}
export function invalidateExtracted(value?:LoreBook|null):LoreBook|null {
 return packBooks(attachedBooks(value).map(b=>b.source==="extracted"?{...b,confirmed:false}:b));
}
/** 장르에 맞는 준비된 설정집. 이름이 같으면 그것, 아니면 장르 글에 이름이 든 것 중 가장 긴 이름(「헌터·게이트」→「헌터」). */
export function bookForGenre(books:LoreBook[],genre:string):LoreBook|undefined{
 const g=(genre||"").trim();if(!g)return undefined;
 return books.find(b=>b.name===g)??books.filter(b=>b.name&&g.includes(b.name)).sort((a,b)=>b.name.length-a.name.length)[0];
}
