"""Real Dify restoration regression on existing test-account recycle photos.

Temporarily restores album_test_a recycled images and returns them to their original
recycle state in finally. No permanent deletion or model-based image processing.
"""
import json
import subprocess
import agent_business_smoke as base
from agent_album_recycle_smoke import turn

checks = []


def check(name, condition):
    checks.append({'name': name, 'passed': bool(condition)})
    print(name, 'PASS' if condition else 'FAIL', flush=True)
    assert condition, name


def main():
    subprocess.run(['docker','cp',str(base.ROOT/'scripts/dify/smoke_turn.py'),
                    'docker-api-1:/tmp/dify-recycle-turn.py'], check=True, capture_output=True)
    accounts = dict(base.sql("SELECT account,id FROM user WHERE account IN ('album_test_a','album_test_b')"))
    owner, other = accounts['album_test_a'], accounts['album_test_b']
    check('configured_test_owner', int(base.RUNTIME['AGENT_OWNER_USER_ID']) == owner)
    photos = [row[0] for row in base.sql("SELECT uf.file_id FROM user_file uf JOIN file f ON f.file_id=uf.file_id WHERE uf.user_id=%s AND uf.is_deleted=1 AND f.category='image' ORDER BY uf.file_id", (owner,))]
    check('bounded_recycled_test_images_available', 0 < len(photos) <= 50)
    originals = {file:base.snapshot(owner,file) for file in photos}
    deleted_times = dict(base.sql('SELECT file_id,deleted_time FROM user_file WHERE user_id=%s AND is_deleted=1', (owner,)))
    other_before = base.sql('SELECT file_id,is_deleted FROM user_file WHERE user_id=%s ORDER BY file_id', (other,))
    conversation = None
    try:
        first = turn('把回收站的照片恢复')
        conversation = first['conversation_id']
        check('real_restore_preview_reply', '恢复' in first['answer'] and '确认' in first['answer'] and '失败' not in first['answer'])
        rows = base.sql('SELECT action,family,status,payload_json FROM agent_pending_action WHERE user_id=%s AND conversation_id=%s ORDER BY id DESC LIMIT 1', (owner,conversation))
        check('p3_pending_matches_actual_recycle_scope', len(rows)==1 and rows[0][:3]==('restore_files','p3_action','PREVIEWED') and set(json.loads(rows[0][3])['fileIds'])==set(photos))
        check('preview_leaves_recycle_unchanged', all(base.snapshot(owner,f)==originals[f] for f in photos))
        second = turn('确认',conversation)
        rows = base.sql('SELECT status,actual_affected_file_count FROM agent_pending_action WHERE user_id=%s AND conversation_id=%s ORDER BY id DESC LIMIT 1', (owner,conversation))
        check('confirmed_restore_succeeded', rows == (('SUCCEEDED',len(photos)),))
        check('images_active_after_restore', all(base.snapshot(owner,f)['relation']==((0,),) for f in photos))
        empty = base.call('previewP3Action', {'action':'restore_files','allRecycleImages':True}, 'empty-restore')
        check('empty_recycle_is_noop_without_credentials', empty.get('requiresConfirmation') is False and not empty.get('pendingActionId') and '没有可恢复' in empty.get('summary',''))
    finally:
        active = [f for f in photos if base.snapshot(owner,f)['relation']==((0,),)]
        for start in range(0,len(active),20):
            preview=base.call('previewP4Action',{'action':'move_files_to_recycle_bin','fileIds':active[start:start+20]},'cleanup-restore')
            base.call('executeP4Action',base.credentials(preview),'cleanup-restore')
        # Re-recycling must not extend the original test rows' retention periods.
        for file in active:
            base.sql('UPDATE user_file SET deleted_time=%s WHERE user_id=%s AND file_id=%s AND is_deleted=1', (deleted_times[file],owner,file))
        check('original_test_state_restored', all(base.snapshot(owner,f)==originals[f] for f in photos))
        check('original_recycle_timestamps_preserved', all(dict(base.sql('SELECT file_id,deleted_time FROM user_file WHERE user_id=%s AND is_deleted=1',(owner,))).get(f)==deleted_times[f] for f in photos))
        check('second_account_unchanged', other_before == base.sql('SELECT file_id,is_deleted FROM user_file WHERE user_id=%s ORDER BY file_id',(other,)))
        (base.ROOT/'evaluation/observations/agent-restore-chat-20260915.json').write_text(json.dumps({'conversationId':conversation,'photoCount':len(photos),'checks':checks,'finalPhotoState':'original recycle state, including timestamps','permanentDeleteTested':False},ensure_ascii=False,indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
