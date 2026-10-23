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
 def test_extraction_rejects_invented_evidence_and_remains_draft(self):
  result=lore_extract.normalize({'fixed_settings':'민수는 소년이다','entries':[{'title':'원문','content':'소년','keys':['민수'],'evidence':'민수는 소년이다'},{'title':'환각','content':'날 수 있다','evidence':'날 수 있다'}]},'민수는 소년이다. 다리를 건넜다.')
  self.assertFalse(result['confirmed']);self.assertEqual(len(result['entries']),1)
  with self.assertRaises(ValueError):lore_extract.normalize({'entries':'wrong'},'')
 def test_collection_uses_all_books_and_detach_removes_only_one(self):
  second={**self.book,'fixed_settings':'두번째 세계 규칙','entries':[]}
  char={'lorebook':{'confirmed':True,'books':[self.book,second]}}
  text=userbook.block(char,'열쇠')
  self.assertIn('주인공은 날지 못한다',text);self.assertIn('두번째 세계 규칙',text)
  char['lorebook']['books']=[second]
  self.assertNotIn('주인공은 날지 못한다',userbook.block(char));self.assertIn('두번째 세계 규칙',userbook.block(char))
if __name__=='__main__':unittest.main()
