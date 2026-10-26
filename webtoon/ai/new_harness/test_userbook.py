import unittest, tempfile, json
from pathlib import Path
from unittest.mock import patch
import run as R
import own, userbook, lore_extract, continuation
class UserbookTest(unittest.TestCase):
 def setUp(self):
  self.book={'name':'도시의 비밀','confirmed':True,'fixed_settings':'주인공은 날지 못한다','progress_state':'처음에는 비밀을 몰랐다','entries':[{'title':'빛나는 열쇠','content':'열쇠는 밤에만 빛난다','keys':['열쇠'],'enabled':True,'always':False},{'title':'불사용','content':'삭제된 정보','keys':[],'enabled':False,'always':True}]}
  self.char=R.normalize({'name':'민수','description':'다정함','genre':'헌터','story':'열쇠를 줍는다','lorebook':self.book})
 def test_detach_and_unconfirmed_yield_no_memory(self):
  self.assertEqual(userbook.block({}), '');self.assertEqual(userbook.block({'lorebook':{**self.book,'confirmed':False}}),'')
  self.assertNotIn('삭제된 정보',userbook.block(self.char,'열쇠'))
 def test_quick_seeded_cast_and_scene_exclude_other_world(self):
  with tempfile.TemporaryDirectory() as d,patch.object(R,'world_text_for',side_effect=AssertionError('old world')),patch.object(R,'genre_lore_section',side_effect=AssertionError('old lore')),patch.object(R,'genre_samples_for',side_effect=AssertionError('old sample')):
   self.assertIn('주인공은 날지 못한다',R.story_input_block(self.char))
   self.assertIn('주인공은 날지 못한다',R.seeded_input_block(self.char))
   R.stage_cast(Path(d),self.char,True)
   self.assertIn('주인공은 날지 못한다',(Path(d)/'cast_prompt.txt').read_text())
   self.assertIn('열쇠는 밤에만 빛난다',R.scene_input_block(self.char,{'n':1,'body':'열쇠를 줍는다'}))
 def test_no_book_keeps_original_genre_path(self):
  char={**self.char,'lorebook':{}}
  with patch.object(R,'world_text_for',return_value='장르 배경'),patch.object(R,'genre_lore_section',return_value=[]),patch.object(R,'genre_samples_for',return_value=''):
   self.assertIn('장르 배경',R.story_input_block(char))
 def test_continuation_omits_original_fixed_and_progress(self):
  text=own.input_block(self.char,continuation=True)
  self.assertNotIn('처음에는 비밀을 몰랐다',text);self.assertNotIn('주인공은 날지 못한다',text);self.assertIn('열쇠는 밤에만 빛난다',text)
 def test_rescene_keeps_selected_entries(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);R.write_json(p/'scenes.json',{'scenes':[{'n':1,'scene':'열쇠를 줍는다'}]})
   with patch.object(R,'direction_of',return_value={'n':1,'body':'열쇠를 줍는다'}):self.assertIn('열쇠는 밤에만 빛난다',own.rescene_input(p,self.char,1,[],''))
 def test_extraction_one_entry_per_subject_and_adds_to_existing(self):
  rows,more,_=lore_extract.book_entries([{'title':'열쇠','keys':['열쇠'],'type':'item','text':'- 밤에만 빛난다'},{'title':'열 쇠','keys':['은열쇠'],'type':'item','text':'- 민수가 주웠다'},
                                       {'title':'달의 도시','keys':['달의 도시'],'type':'world','always':True,'text':'- 밤이 끝나지 않는다'},{'title':'빈 것','text':''}])
  self.assertEqual([r['id'] for r in rows],['L0001','L0002']);self.assertEqual(more,{})
  self.assertEqual(rows[0]['content'],'- 밤에만 빛난다\n- 민수가 주웠다');self.assertEqual(rows[0]['keys'],['열쇠','은열쇠'])
  self.assertTrue(rows[1]['always']);self.assertFalse(rows[0]['always'])
  new,more,_=lore_extract.book_entries([{'title':'열쇠','text':'- 문을 연다'},{'title':'문','keys':['문'],'text':'- 새 항목'}],rows)
  self.assertEqual([(r['id'],r['title']) for r in new],[('L0003','문')]);self.assertEqual(more,{'L0001':'- 문을 연다'})
  book=lore_extract.apply_book(rows,new,more)
  self.assertEqual(book[0]['content'],'- 밤에만 빛난다\n- 민수가 주웠다\n- 문을 연다');self.assertTrue(book[0]['updated'])
  self.assertNotIn('updated',book[1]);self.assertTrue(book[2]['new'])
 def test_world_seed_gets_named_and_grows_instead_of_a_second_world(self):
  seed=[{'id':'R000','title':'세계 배경','kind':'world','keys':[],'content':'장르의 세계 설명','always':True,'enabled':True}]
  new,more,renamed=lore_extract.book_entries([{'title':'에테르넷','keys':['게임'],'type':'world','always':True,'named':True,'text':'로그아웃 시점은 시스템이 정한다'}],seed)
  self.assertEqual(new,[]);self.assertEqual(more,{'R000':'로그아웃 시점은 시스템이 정한다'})
  book=lore_extract.apply_book(seed,new,more,renamed)
  self.assertEqual(book[0]['title'],'에테르넷');self.assertTrue(book[0]['named']);self.assertTrue(book[0]['always'])
  self.assertEqual(book[0]['keys'],['에테르넷','게임']);self.assertEqual(book[0]['content'],'장르의 세계 설명\n로그아웃 시점은 시스템이 정한다')
  named=[{**book[0]}]  # 이미 이름이 있는 세계는 다시 이름을 바꾸지 않는다
  _,_,again=lore_extract.book_entries([{'title':'다른 이름','type':'world','named':True,'text':'새 사실'}],named)
  self.assertEqual(again,{})
 def test_entry_name_always_counts_as_keyword(self):
  rows=[{'title':'김시하','keys':['선배'],'content':'출판사 편집자'}]
  self.assertEqual(userbook.select(rows,'김시하가 웃었다'),rows)
  self.assertEqual(userbook.select(rows,'선배가 웃었다'),rows)
  self.assertEqual(userbook.select(rows,'아무도 없다'),[])
 def test_progress_merge_matches_experiment(self):
  rows=[{'key':'P001','title':'위치','text':'다리 앞'},{'key':'L0001','title':'열쇠','text':'민수가 갖고 있다'}]
  merged=lore_extract.merge_progress(rows,[{'key':'','title':'약속','text':'내일 만나기로 했다'},{'key':'L0001','title':'열쇠','text':'잃어버렸다'}],['P001'])
  self.assertEqual([(r['key'],r['text']) for r in merged],[('L0001','잃어버렸다'),('P001','내일 만나기로 했다')])  # 실험처럼 남은 P 개수 다음 번호
 def test_rules_always_entries_ranked_and_progress_overrides(self):
  book={'confirmed':True,'entries':[{'id':'L0001','title':'열쇠','content':'밤에만 빛난다','keys':['열쇠']},
        {'id':'L0002','title':'문','content':'열쇠로 열린다','keys':['문','열쇠']},{'id':'L0003','title':'바다','content':'멀다','keys':['바다']},
        {'id':'R001','title':'비밀','content':'정체를 말할 수 없다','keys':[],'always':True}],
        'progress':[{'key':'L0001','title':'열쇠','text':'부서졌다'},{'key':'P001','title':'위치','text':'문 앞'}]}
  text=userbook.block({'lorebook':book},'열쇠를 들고',continuation=True)
  self.assertIn('정체를 말할 수 없다',text);self.assertIn('→ 지금: 부서졌다',text);self.assertIn('- 위치: 문 앞',text)
  self.assertNotIn('멀다',text)
  self.assertIn('열쇠로 열린다',text)  # 순위는 예산 안에 넣을 순서만 정하고, 넣는 순서는 원래 순서다(실험과 같다)
  self.assertEqual([e['id'] for e in userbook.select(book['entries'][:3],'열쇠 문',budget=10)],['L0002'])
  self.assertNotIn('밤에만',userbook.block({'lorebook':{**book,'progress':[]}},'아무 상관 없는 글'))
 def test_collection_uses_all_books_and_detach_removes_only_one(self):
  second={**self.book,'fixed_settings':'두번째 세계 규칙','entries':[]}
  char={'lorebook':{'confirmed':True,'books':[self.book,second]}}
  text=userbook.block(char,'열쇠')
  self.assertIn('주인공은 날지 못한다',text);self.assertIn('두번째 세계 규칙',text)
  char['lorebook']['books']=[second]
  self.assertNotIn('주인공은 날지 못한다',userbook.block(char));self.assertIn('두번째 세계 규칙',userbook.block(char))
if __name__=='__main__':unittest.main()
