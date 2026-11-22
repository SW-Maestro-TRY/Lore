import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageChops
import lou
import run


class LouTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.book = self.root / "episode"
        (self.book / "pages").mkdir(parents=True)
        self.page = self.book / "pages/page02.png"
        image = Image.new("RGB", (600, 1800), "white")
        image.putpixel((599,1799), (255,0,0))
        image.save(self.page)
        lou.write_json(self.book/"input.json", {"name":"가"})
        lou.write_json(self.book/"scenes.json", {"scenes":[{"what":"가"}, {"acting":"가"}, {"where":"가"}]})
        self.pin = dict(n=1,page=2,x=100,y=100,note="")
        self.req = dict(text="",active_page=2,pins=[self.pin],lang="ko")
        self.ask = dict(reply="확인했어요.",found=[dict(pin=1,page=2,who="가",what="person")],
                        plans=[dict(method="keep",pages=[2],changes="손",keeps="구도",note="가의 손")])

    def invoke(self, kind, req, replies, seen=None):
        """seen 은 글자 읽기의 답이다 — 그림 파일 이름의 앞머리(pin1 · read02 · before · after)로 고른다."""
        path = self.root/"input.json"
        out = self.root/"out.json"
        lou.write_json(path, req)
        def reader(picture):
            lines = next((v for k, v in (seen or {}).items() if picture.name.startswith(k)), [])
            return lines, {"stage":"LOU","model":"reader"}
        with patch.object(run,"RUNS_DIR",self.root), patch.object(lou,"read_text",side_effect=reader), \
                patch.object(lou,"confirm_changes",side_effect=lambda run_dir, changed, cut, found: changed), \
                patch.object(lou.llm,"Call") as factory:
            factory.return_value.side_effect = [(s,{"stage":"LOU","model":"fixture"}) for s in replies]
            run.main(["--run-id","episode","--lou-"+kind,str(path),"--lou-out",str(out)])
            factory.assert_called_once_with("LOU")
            self.assertEqual(len(replies),factory.return_value.call_count)
        return lou.read(out)

    def test_limits(self):
        pins = [dict(self.pin,n=i,page=i) for i in range(1,9)]
        self.assertEqual(([1,2,3],[4,5,6,7,8]),lou.selection(dict(pins=pins)))
        with self.assertRaises(ValueError):
            lou.selection(dict(pins=pins+[self.pin]))
        self.assertEqual(([2],[]),lou.selection(dict(active_page=2)))

    def test_edge_crops_and_markers(self):
        paths = lou.pictures(self.book,self.root,2,[dict(self.pin,n=2,x=0,y=0),self.pin])
        with Image.open(paths[0]) as full, Image.open(self.page) as original:
            self.assertEqual((512,1536),full.size)
            self.assertIsNotNone(ImageChops.difference(original.resize(full.size),full).getbbox())
        with Image.open(paths[1]) as first, Image.open(paths[2]) as last:
            self.assertEqual((1200,1200),first.size)
            self.assertEqual((1200,1200),last.size)
            self.assertGreater(last.getpixel((1199,1199))[0],last.getpixel((1199,1199))[1])

    def test_retry_and_normalize(self):
        bad = dict(self.ask,plans=[dict(self.ask["plans"][0],method="other")])
        result = self.invoke("ask",self.req,[json.dumps(bad),json.dumps(self.ask)])
        self.assertEqual([3],result["plans"][0]["more_pages"])
        self.assertIsNone(result["plans"][0]["scene"]["what"])
        self.assertEqual("",result["found"][0]["quote"])
        calls = lou.read(self.book/"meta.json")["calls"]
        self.assertEqual(["reader"]*6+["fixture"]*2,[c["model"] for c in calls])   # 띠 다섯 · 짚은 곳 하나 · 묻기 두 번

    def test_unpinned_finding_is_kept(self):
        answer = dict(self.ask,found=[dict(pin=0,page=2,who="나",what="bubble"),
                                      dict(pin=1,page=9,who="다",what="person"),*self.ask["found"]])
        result = self.invoke("ask",self.req,[json.dumps(answer)])
        self.assertEqual([(0,"나"),(1,"가")],[(f["pin"],f["who"]) for f in result["found"]])
        self.assertEqual("keep",result["plans"][0]["method"])

    def test_wrong_letters_are_found_without_the_script(self):
        lou.write_json(self.book/"scenes.json", {"scenes":[
            {"acting":"가가 “밤마다 그쪽에서 비명이 들려, 새벽엔 총소리도 났고.”라고 말한다.","narration":["날이 밝았다."]}]})
        seen = {"read02":["밤마다 그쪽에서 비명이 들려, 새벽엔 총소리도 났고.","날이 밝았다.","쾅"],
                "pin1":["밤마다 그쪽에서 비명이 들려, 새벽엔 총소리도 났오.","밤마다 그쪽에서"]}
        result = self.invoke("ask",self.req,[json.dumps(self.ask)],seen=seen)
        self.assertEqual([1],[d["pin"] for d in result["text_diff"]])
        self.assertEqual(["밤마다 그쪽에서 비명이 들려, 새벽엔 총소리도 났오."],[d["seen"] for d in result["text_diff"]])
        self.assertIn("났고",result["text_diff"][0]["want"])

    def test_text_fix_uses_the_pinned_spot(self):
        plan = dict(method="text",pages=[2],changes="오자",keeps="그림",note="",
                    text=dict(page=2,pin=1,x=10,y=10,text="총소리도  났고."))
        result = self.invoke("ask",self.req,[json.dumps(dict(self.ask,plans=[plan]))])
        self.assertEqual(dict(page=2,pin=1,x=100,y=100,text="총소리도 났고."),result["plans"][0]["text"])

    def test_short_name_matches_scene(self):
        self.assertTrue(lou.name_in("디온 아스칼라","디온이 들어온다"))
        self.assertFalse(lou.name_in("디온 아스칼라","레오가 들어온다"))

    def test_safe_fallback(self):
        result = self.invoke("ask",self.req,["invalid","{}"])
        self.assertTrue(result["reply"])
        self.assertFalse(result["plans"])

    def test_check(self):
        check = dict(ok=True,reply="확인했어요.",kept=["구도"],changed=["손"],unexpected=[])
        req = dict(page=2,before=str(self.page),after=str(self.page),note="손",method="keep",lang="ko")
        result = self.invoke("check",req,[json.dumps(check)],seen={"before":["물린 사람"],"after":["몰린 사람"]})
        self.assertEqual(dict(check,text_changed=[dict(before="물린 사람",after="몰린 사람")],text_expected=[],retry_note=""),result)
        result = self.invoke("check",dict(req,method="redraw"),[json.dumps(check)])
        self.assertEqual(dict(check,retry_note=""),result)

    def test_text_fix_on_a_pin_from_another_page_is_refused(self):
        req = dict(self.req,pins=[self.pin,dict(self.pin,n=2,page=3)])
        lou.write_json(self.book/"scenes.json", {"scenes":[{"what":"가"},{"what":"가"},{"what":"가"}]})
        Image.new("RGB",(600,1800),"white").save(self.book/"pages/page03.png")
        plan = dict(method="text",pages=[3],changes="오자",keeps="그림",note="",text=dict(page=3,pin=1,x=10,y=10,text="글"))
        result = self.invoke("ask",req,[json.dumps(dict(self.ask,plans=[plan]))]*2)
        self.assertEqual([],result["plans"])

    def test_text_fix_on_a_pin_from_a_skipped_page_is_refused(self):
        lou.write_json(self.book/"scenes.json", {"scenes":[{"what":"가"}]*5})
        for n in (3, 4, 5):
            Image.new("RGB",(600,1800),"white").save(self.book/f"pages/page{n:02}.png")
        req = dict(self.req,pins=[dict(self.pin,n=i,page=i+1) for i in range(1,5)])
        plan = dict(method="text",pages=[2],changes="오자",keeps="그림",note="",text=dict(page=2,pin=4,x=10,y=10,text="글"))
        result = self.invoke("ask",req,[json.dumps(dict(self.ask,plans=[plan]))]*2)
        self.assertEqual([],result["plans"])
        self.assertEqual([5],result["skipped_pages"])

    def test_check_relative_path_is_refused_even_if_it_exists(self):
        import os
        path = self.root/"input.json"
        lou.write_json(path, dict(page=2,before="pages/page02.png",after=str(self.page),note="손",method="keep",lang="ko"))
        here = os.getcwd()
        os.chdir(self.book)
        try:
            self.assertTrue(Path("pages/page02.png").exists())
            with self.assertRaises(ValueError):
                lou.process(self.book,"check",path,self.root/"out.json")
        finally:
            os.chdir(here)

    def test_check_only_compares_pictures_of_this_work(self):
        outside = self.root/"other.png"
        Image.new("RGB",(10,10)).save(outside)
        path = self.root/"input.json"
        lou.write_json(path, dict(page=2,before=str(outside),after=str(self.page),note="손",method="keep",lang="ko"))
        with self.assertRaises(ValueError):
            lou.process(self.book,"check",path,self.root/"out.json")
        lou.write_json(path, dict(page=2,before="pages/page02.png",after=str(self.page),note="손",method="keep",lang="ko"))
        with self.assertRaises(ValueError):          # 상대 경로는 어디 기준인지 모호하다
            lou.process(self.book,"check",path,self.root/"out.json")

    def test_text_drift_counts_repeats_and_ignores_line_splits(self):
        self.assertEqual([dict(before="간다 간다",after=""),dict(before="",after="추가 문장")],
                         lou.text_changed(["간다 간다","간다 간다"],["간다 간다","추가 문장"]))
        self.assertEqual([], lou.text_changed(["다시 간다"],["다시","간다"]))
        self.assertEqual([dict(before="",after="새로 생긴 줄")], lou.text_changed(["그대로"],["그대로","새로 생긴 줄"]))

    def test_short_typos_and_rejoined_lines(self):
        self.assertEqual(["가고라"],[d["seen"] for d in lou.text_diff(2,["가고라","가거라"],{"acting":"“가거라.”"})])
        self.assertEqual([],lou.text_diff(2,["가거라"],{"acting":"“가거라.” “가고라.”"}))
        self.assertEqual([dict(before="그만",after="그만해")], lou.text_changed(["어서 와","그만"],["어서","와","그만해"]))
        self.assertEqual([dict(before="그만",after="그만해")],
                         lou.text_changed(["우리 모두 여기 모여","그만"],["우리","모두","여기","모여","그만해"]))

    def test_result_is_not_written_through_a_link(self):
        target = self.root/"keep.json"
        lou.write_json(target, {"keep": True})
        link = self.root/"out-link.json"
        link.symlink_to(target)
        path = self.root/"input.json"
        lou.write_json(path, dict(page=2,before=str(self.page),after=str(self.page),note="손",method="redraw",lang="ko"))
        with patch.object(lou.llm,"Call") as factory, self.assertRaises(ValueError):
            factory.return_value.side_effect = [(json.dumps(dict(ok=True,reply="네",kept=[],changed=[],unexpected=[])),{"stage":"LOU"})]
            lou.process(self.book,"check",path,link)
        self.assertEqual({"keep": True}, lou.read(target))

    def test_asked_text_changes_are_counted_apart(self):
        req = dict(page=2,before=str(self.page),after=str(self.page),method="keep",lang="ko",
                   note="1번째 컷 효과음 「의자 소리..」를 「드르륵」으로 바꾼다. 나머지는 그대로.")
        check = dict(ok=True,reply="확인했어요.",kept=[],changed=[],unexpected=[])
        result = self.invoke("check",req,[json.dumps(check)],
                             seen={"before":["의자 소리..","그대로 둔 말"],"after":["드르륵","그대로 둔 말","몰래 바뀐 말"]})
        self.assertEqual([dict(before="",after="몰래 바뀐 말")],result["text_changed"])
        self.assertEqual({"의자 소리..","드르륵"},{c["before"] or c["after"] for c in result["text_expected"]})

    def test_check_offers_a_retry_note_only_when_something_else_changed(self):
        req = dict(page=2,before=str(self.page),after=str(self.page),method="keep",lang="ko",note="손만")
        bad = dict(ok=False,reply="옆 사람 머리도 바뀌었어요.",kept=[],changed=["손"],unexpected=["옆 사람 머리"],
                   retry_note="손만. 2번째 컷 검은 머리 남자는 그대로.")
        self.assertEqual("손만. 2번째 컷 검은 머리 남자는 그대로.",
                         self.invoke("check",req,[json.dumps(bad)],seen={})["retry_note"])
        good = dict(bad,ok=True,unexpected=[],retry_note="쓸데없는 지시")
        self.assertEqual("", self.invoke("check",req,[json.dumps(good)],seen={})["retry_note"])

    def test_confirm_drops_misreads_and_keeps_real_changes(self):
        bands = [self.root/"b0.png", self.root/"a0.png"]
        for path in bands:
            Image.new("RGB",(10,10)).save(path)
        cut = [[bands[0]],[bands[1]]]
        found = [["가겠다고 했어","라옴은 갔다"],["가져다고 했어","라움은 갔다"]]
        changed = [dict(before="가겠다고 했어",after="가져다고 했어"),dict(before="라옴은 갔다",after="라움은 갔다"),
                   dict(before="어디에도 없는 줄",after="")]
        answers = {"가겠다고 했어": dict(same=True,found=True,before="",after=""),
                   "라옴은 갔다": dict(same=False,found=True,before="라옴은 갔다",after="라움은 갔다")}

        class Fake:
            provider = model = "fake"
            def __call__(self, prompt, images=None, temperature=None):
                line = json.loads(prompt.rsplit("\n",1)[1])["before"]
                return json.dumps(answers[line], ensure_ascii=False), {"stage":"LOU"}

        with patch.object(lou,"Call_for_text",Fake), patch.object(lou.llm,"load_images",side_effect=lambda p: p):
            got = lou.confirm_changes(self.book, changed, cut, found)
        self.assertEqual([dict(before="라옴은 갔다",after="라움은 갔다"),dict(before="어디에도 없는 줄",after="")], got)

    def test_verified_keep_retries_once_from_the_original(self):
        source = self.book/"pages/versions/page02.v1.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB",(600,1800),"blue").save(source)
        drawn, seen_before = [], []

        sources = []

        def draw(note, source=None):
            drawn.append(note)
            sources.append(Path(source).name)
            Image.new("RGB",(600,1800),"green").save(self.page)

        def run_check(outcomes):
            answers = iter(outcomes)
            def fake(run_dir, kind, request, output, dry=False):
                req = lou.read(request)
                seen_before.append(Image.open(req["before"]).convert("RGB").getpixel((0,0)))
                lou.write_json(output, next(answers))
            return fake

        bad = dict(ok=False,reply="옆 사람도 바뀜",kept=[],changed=[],unexpected=["옆 사람 머리"],retry_note="손만. 옆 사람은 그대로.")
        good = dict(ok=True,reply="됐어요",kept=[],changed=["손"],unexpected=[],retry_note="")
        out = self.root/"verify.json"
        with patch.object(lou,"process",side_effect=run_check([bad,good])):
            lou.verified_keep(self.book,2,source,"손만","ko",draw,out)
        result = lou.read(out)
        self.assertEqual(["손만","손만. 옆 사람은 그대로."],drawn)
        self.assertEqual([(0,0,255),(0,0,255)],seen_before)                    # 두 번 다 처음 원본과 견준다
        self.assertEqual([".lou-source-02.png"]*2,sources)                     # 두 번 다 떠 둔 원본에서 그린다
        self.assertTrue(result["ok"])
        self.assertEqual([False,True],[a["ok"] for a in result["attempts"]])
        self.assertEqual("",result["advice"])
        drawn.clear()
        with patch.object(lou,"process",side_effect=run_check([bad,bad])):
            lou.verified_keep(self.book,2,source,"손만","ko",draw,out)
        self.assertEqual(2,len(drawn))                                          # 세 번째는 없다
        self.assertEqual("redraw",lou.read(out)["advice"])
        self.assertFalse((self.book/"pages/.lou-source-02.png").exists())

    def test_text_drift(self):
        self.assertEqual([dict(before="물린 사람을 보면",after="몰린 사람을 보면"),dict(before="쾅쾅",after="")],
                         lou.text_changed(["물린 사람을 보면","그게 다야.","쾅쾅"],["그게 다야.","몰린 사람을 보면"]))
