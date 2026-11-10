package com.lore.webtoon.character;

/** 캐릭터 그림이 어디서 왔나. 나중에 무엇이 더 잘 되는지 보려고 남긴다. */
public enum CharacterSource {
    /** 사람이 올린 사진을 읽어 그렸다. */
    PHOTO,
    /** 이름과 설명만으로 그렸다 — 자캐 그림이 없는 사람의 길. */
    PROMPT,
    /** 우리가 올려 둔 것. 처음 온 사람이 만들 것이 없어도 바로 고를 수 있게. */
    BUILTIN,
    /** 사용자가 올린 이미지와 설정을 그대로 보관한다. AI 호출 없음. */
    UPLOAD,
    /** AI로 생성한 뒤 사용자가 원본 이미지로 교체. 생성 무료 횟수는 유지한다. */
    REPLACED_UPLOAD,
}
