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

class LegacyEpisodeTest(unittest.TestCase):
    """후보 넷 중 고르기로 만든 기존 1화 — 인물·세계가 2화로 맞게 넘어가는가."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.src=Path(self.temp.name)/"ep1"; self.src.mkdir()
        def put(name,value): (self.src/name).write_text(json.dumps(value,ensure_ascii=False),encoding="utf-8")
        put("input.json",{"name":"민준","description":"신입 헌터","genre":"헌터"})
        put("cast.json",[{"name":"서이안","look":"은회색 장발"},{"name":"루나","look":"은빛 단발"},{"name":"강도현","look":"붉은 망토"}])
        put("directions.json",[{"n":1,"title":"게이트","genre":"헌터","body":"민준은 심사장에서 등급 재측정을 받는다.","lead":{"name":"서이안"}},
                               {"n":2,"title":"다른 후보","body":"루나와 강도현이 나온다."}])
        put("pick.json",{"n":1})
        put("scenes.json",{"cast":[{"name":"이안","appearance":"은회색 장발"},{"name":"감독관","appearance":"제복"}],"scenes":[]})
        put("lore.json",{"world":"hunter_gate","directions":[["hg-inner-time"],["hg-shelter"]]})
    def test_only_people_in_the_chosen_episode_carry_over(self):
        names=[c["name"] for c in C.appeared_cast(self.src)]
        self.assertEqual(names,["서이안","감독관"])   # 「이안」은 서이안과 같은 사람, 루나·강도현은 고르지 않은 후보
    def test_world_of_the_first_episode_is_seeded_into_the_book(self):
        seed=C.world_seed(self.src)
        self.assertEqual(seed[0]["id"],"R000"); self.assertTrue(seed[0]["always"])
        self.assertIn("W-hg-inner-time",[e["id"] for e in seed]); self.assertNotIn("W-hg-shelter",[e["id"] for e in seed])
        (self.src/"input.json").write_text(json.dumps({"name":"민준","genre":"헌터","lorebook":{"name":"내 세계","confirmed":True}}),encoding="utf-8")
        self.assertEqual(C.world_seed(self.src),[])  # 설정집을 붙여 만든 1화는 그 설정집이 세계다
    def test_digest_dry_run_writes_the_book_and_progress_prompts(self):
        packet=Path(self.temp.name)/"packet.json"; out=Path(self.temp.name)/"digest"/"result.json"; out.parent.mkdir()
        packet.write_text(json.dumps({"source_dir":str(self.src),"episode_number":1,"source_scenes":["심사장 장면"],"previous":None},ensure_ascii=False))
        self.assertIsNone(C.stage_digest(packet,out,True))
        for name,head in (("lore_book_prompt.txt","대상 하나가 항목 하나다"),("lore_progress_prompt.txt","[현재 진행 상태]")):
            text=(out.parent/name).read_text()
            self.assertIn(head,text); self.assertIn("등급 재측정을 받는다",text)
        self.assertFalse(out.exists())

    def test_carried_entries_without_ids_get_ids_so_digest_adds_to_them(self):
        # 1화에 붙인 설정집 항목은 번호가 없다 — 번호를 달아야 같은 대상이 새 항목으로 또 생기지 않는다
        packet=Path(self.temp.name)/"packet.json"; out=Path(self.temp.name)/"digest2"/"result.json"; out.parent.mkdir()
        book={"entries":[{"title":"사역마 배정식","content":"입학 첫날 사역마가 학생을 고른다.","keys":["사역마"],"always":False}],"progress":[]}
        packet.write_text(json.dumps({"source_dir":str(self.src),"episode_number":1,"source_scenes":["심사장 장면"],"previous":book},ensure_ascii=False))
        C.stage_digest(packet,out,True)
        import lore_extract as X
        rows,more,_=X.book_entries([{"title":"사역마 배정식","type":"item","text":"사역마는 기다리다 지치기도 한다."}],
                                   [{**book["entries"][0],"id":"K001"}])
        self.assertEqual(rows,[]); self.assertIn("K001",more)
        self.assertIn("[이미 있는 설정]\n- 사역마 배정식",(out.parent/"lore_book_prompt.txt").read_text())

    def test_scene_field_written_on_following_lines_is_kept(self):
        # 「인물의 행동과 표정:」 아래 여러 줄로 쓴 대사가 빈칸이 되던 것(2026-10-26)
        import run as R
        text="장면 1:\n직전 상태: 앞\n장소와 상황: 복도\n벌어지는 일: 목소리\n인물의 행동과 표정:\n- 교사: 문고리를 잡는다.\n- 복도 끝: \"여기 맞죠?\"\n겉모습·소지품·동행: 시트 그대로\n끝나는 상태: 돌아본다\n나레이션: 통금 뒤\n"
        got=R.parse_scenes(text)["scenes"][0]
        self.assertIn("\"여기 맞죠?\"",got["acting"]); self.assertIn("문고리",got["acting"])
        self.assertEqual(got["look"],"시트 그대로"); self.assertEqual(got["narration"],["통금 뒤"])


if __name__=="__main__": unittest.main()
