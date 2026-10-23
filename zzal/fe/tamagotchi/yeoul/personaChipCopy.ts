import type { ZzalLocale } from '../../lib/locale';
/** Labels only: submitted chip values and authored text remain unchanged. */
const CHIPS:Record<string,[string,string]>={
"학생":["Student", "学生"],
"직장인":["Office worker", "会社員"],
"가게 운영":["Shop owner", "店を営む"],
"모험가":["Adventurer", "冒険者"],
"마법사":["Mage", "魔法使い"],
"그 외":["Other", "その他"],
"음식":["Food", "食べ物"],
"간식":["Snacks", "おやつ"],
"음료":["Drinks", "飲み物"],
"놀이":["Play", "遊び"],
"장소":["Places", "場所"],
"동물":["Animals", "動物"],
"날씨":["Weather", "天気"],
"소리":["Sounds", "音"],
"벌레":["Bugs", "虫"],
"어둠":["Darkness", "暗闇"],
"물":["Water", "水"],
"간섭":["Interference", "干渉"],
 '현대':['Modern','現代'],'역사·시대물':['Historical','歴史・時代もの'],'판타지':['Fantasy','ファンタジー'],'미래·SF':['Future / sci-fi','未来・SF'],'디스토피아·아포칼립스':['Dystopia / apocalypse','ディストピア・終末世界'],
 '다정함':['Caring','思いやりがある'],'쾌활함':['Cheerful','明るい'],'차분함':['Calm','落ち着いている'],'담담함':['Even-tempered','淡々としている'],'느긋함':['Easygoing','のんびり'],'능청스러움':['Smooth-talking','飄々としている'],'장난기':['Playful','いたずら好き'],'무뚝뚝함':['Blunt','無愛想'],'낯가림':['Shy with strangers','人見知り'],'당당함':['Confident','堂々としている'],'섬세함':['Sensitive','繊細'],'자유분방함':['Free-spirited','自由奔放']
};
export function personaChipLabel(value:string,locale:ZzalLocale){return locale==='ko'?value:CHIPS[value]?.[locale==='en'?0:1]??value}
