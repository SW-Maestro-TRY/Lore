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
        self.assertEqual(dict(check,text_changed=[dict(before="물린 사람",after="몰린 사람")]),result)
        result = self.invoke("check",dict(req,method="redraw"),[json.dumps(check)])
        self.assertEqual(check,result)

    def test_text_fix_on_a_pin_from_another_page_is_refused(self):
        req = dict(self.req,pins=[self.pin,dict(self.pin,n=2,page=3)])
        lou.write_json(self.book/"scenes.json", {"scenes":[{"what":"가"},{"what":"가"},{"what":"가"}]})
        Image.new("RGB",(600,1800),"white").save(self.book/"pages/page03.png")
        plan = dict(method="text",pages=[3],changes="오자",keeps="그림",note="",text=dict(page=3,pin=1,x=10,y=10,text="글"))
        result = self.invoke("ask",req,[json.dumps(dict(self.ask,plans=[plan]))]*2)
        self.assertEqual([],result["plans"])

    def test_check_only_compares_pictures_of_this_work(self):
        outside = self.root/"other.png"
        Image.new("RGB",(10,10)).save(outside)
        path = self.root/"input.json"
        lou.write_json(path, dict(page=2,before=str(outside),after=str(self.page),note="손",method="keep",lang="ko"))
        with self.assertRaises(ValueError):
            lou.process(self.book,"check",path,self.root/"out.json")

    def test_text_drift_counts_repeats_and_ignores_line_splits(self):
        self.assertEqual([dict(before="간다 간다",after=""),dict(before="",after="추가 문장")],
                         lou.text_changed(["간다 간다","간다 간다"],["간다 간다","추가 문장"]))
        self.assertEqual([], lou.text_changed(["다시 간다"],["다시","간다"]))
        self.assertEqual([dict(before="",after="새로 생긴 줄")], lou.text_changed(["그대로"],["그대로","새로 생긴 줄"]))

    def test_text_drift(self):
        self.assertEqual([dict(before="물린 사람을 보면",after="몰린 사람을 보면"),dict(before="쾅쾅",after="")],
                         lou.text_changed(["물린 사람을 보면","그게 다야.","쾅쾅"],["그게 다야.","몰린 사람을 보면"]))
