/** Legacy rewards remain readable/shareable; they are no longer new progression goals. */
export function isRetiredRewardSeq(seq: number): boolean {
  return (seq >= 9 && seq <= 16) || seq === 101 || seq === 102;
}
export function showInLegacyAlbum(motion: { layer: string; unlocked: boolean; advanced?: { status: string } }): boolean {
  return motion.layer === 'BASIC_1' || motion.unlocked || motion.advanced?.status === 'OPEN';
}
