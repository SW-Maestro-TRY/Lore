"""무료 회귀 검사: 모델은 호출하지 않고 파일 승계와 프롬프트 경계를 확인한다."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import continuation as C
import run as R
import own

class ContinuationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source=Path(self.temp.name)/"source"; self.source.mkdir()
        self.dest=Path(self.temp.name)/"next"; self.dest.mkdir()
        def put(name,value):
            (self.source/name).write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
        (self.source/"sheet.png").write_bytes(b"unchanged-sheet")
        put("input.json",{"name":"민수","description":"높은 곳을 무서워한다","card":{},"fields":{},"genre":"판타지"})
        put("directions.json",[{"n":1,"body":"선택하지 않은 이야기"},{"n":3,"body":"다리를 피해 늦었다"}])
        put("pick.json",{"n":3})
        put("lore.json",{"world":"academy_magic","directions":[["wrong"],[],["right"]]})
        put("cast.json",[{"name":"지수","look":"검은 머리"}])
        self.packet={"source_dir":str(self.source),"source_run":"ep1","episode_number":2,
                     "fixed_settings":"높은 곳을 무서워함. 비밀 공개 금지.","progress_state":"약속에 늦었고 우회로를 알게 됐다.",
                     "source_scenes":["작가가 확정한 마지막 장면"],"request":"일찍 출발한다"}
        self.char=R.normalize(C.prepare_input({"name":"민수","story":"일찍 출발한다","settings":self.packet["fixed_settings"],"title":"","episode":""},self.packet))
        C.initialize(self.dest,self.packet)
        R.write_json(self.dest/"input.json",self.char)
    def test_current_book_selection_overrides_frozen_source_selection(self):
        original=C.read(self.source/"input.json");original["lorebook"]={"confirmed":True,"fixed_settings":"삭제할 설정"};R.write_json(self.source/"input.json",original)
        char={"name":"민수","story":"새 전개","settings":"최신 고정","title":"","episode":"","lorebook":{}}
        self.assertEqual(C.prepare_input(char,self.packet)["lorebook"],{})
        book={"confirmed":True,"books":[{"name":"A","confirmed":True,"fixed_settings":"A"},{"name":"B","confirmed":True,"fixed_settings":"B"}]}
        char["lorebook"]=book
        self.assertEqual(C.prepare_input(char,self.packet)["lorebook"],book)
    def test_selected_body_and_lore_are_reindexed_without_copying_old_story(self):
        packet=C.read(self.dest/"continuation.json")
        self.assertEqual(packet["source_body"],"다리를 피해 늦었다")
        self.assertEqual(C.read(self.dest/"lore.json")["directions"],[["right"]])
        self.assertFalse((self.dest/"directions.json").exists())
        self.assertEqual((self.dest/"sheet.png").read_bytes(),b"unchanged-sheet")
    def test_dry_run_reuses_sheets_without_calling_persona_cast_or_images(self):
        with patch.object(own,"stage_persona",side_effect=AssertionError("new persona")), \
             patch.object(own,"stage_cast",side_effect=AssertionError("new cast")), \
             patch.object(own,"stage_sheet",side_effect=AssertionError("new sheet")):
            own.run_own(self.dest,self.char,True)
        text=(self.dest/"story_prompt.txt").read_text()
        for word in ("2화","고정 설정","진행 상태","다리를 피해 늦었다","일찍 출발한다","비밀"):
            self.assertIn(word,text)
        self.assertNotIn("선택하지 않은 이야기",text)
    def test_restory_keeps_memory_and_scene_generation_only_uses_new_body(self):
        own.restory(self.dest,self.char,True,note="서두르다 넘어지는 전개")
        self.assertIn("서두르다 넘어지는 전개",(self.dest/"story_prompt.txt").read_text())
        R.write_json(self.dest/"directions.json",[{"n":1,"title":"일찍 출발","body":"이번 화의 새로운 사건"}])
        R.write_json(self.dest/"pick.json",{"n":1})
        own.stage_scenes(self.dest,self.char,True)
        text=(self.dest/"scene_prompt.txt").read_text()
        self.assertIn("이번 화의 새로운 사건",text)
        self.assertIn("진행 상태",text)
        self.assertNotIn("다리를 피해 늦었다",text)
    def test_selected_and_scene_only_cast_carry_over(self):
        R.write_json(self.source/"cast.json",[{"name":"지수","look":"검은 머리"},{"name":"미선택 후보","look":"금발"}])
        R.write_json(self.source/"cast_pick.json",{"name":"지수","n":1})
        R.write_json(self.source/"scenes.json",{"cast":[{"name":"민수"},{"name":"새 조연","appearance":"빨간 모자"}]})
        C.initialize(self.dest,self.packet)
        cast=C.read(self.dest/"cast.json")
        self.assertEqual([c["name"] for c in cast],["지수","새 조연"])
        self.assertEqual(cast[1]["look"],"빨간 모자")

    def test_original_episode_has_no_continuation_prompt(self):
        other=Path(self.temp.name)/"original";other.mkdir()
        own.stage_story(other,self.char,True)
        self.assertNotIn("[작가가 확인한 현재 진행 상태",(other/"story_prompt.txt").read_text())

if __name__=="__main__": unittest.main()
