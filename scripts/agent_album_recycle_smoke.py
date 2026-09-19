"""Two real local Dify turns, with independent DB checks and guaranteed test-photo restore.

Run only for the configured album_test_a owner and its existing 测试旅行 album.
Copies scripts/dify/smoke_turn.py into the local API container. No permanent deletion.
"""
import json
import subprocess
from pathlib import Path
import agent_business_smoke as base

ROOT = base.ROOT
CHECKS = []


def check(name, condition):
    CHECKS.append({'name': name, 'passed': bool(condition)})
    print(name, 'PASS' if condition else 'FAIL', flush=True)
    assert condition, name


def turn(query, conversation=None):
    args = {'query': query}
    if conversation:
        args['conversation_id'] = conversation
    proc = subprocess.run(['docker', 'exec', '-i', '-e', 'PYTHONPATH=/app/api', '-w', '/app/api',
                           'docker-api-1', '/app/api/.venv/bin/python', '/tmp/dify-recycle-turn.py'],
                          input=json.dumps(args), text=True, encoding='utf-8', capture_output=True, timeout=90)
    if proc.returncode:
        raise RuntimeError('Dify turn failed; inspect scoped container logs')
    value = subprocess.run(['docker', 'exec', 'docker-api-1', 'cat', '/tmp/cloud-album-recycle-turn.json'],
                           text=True, encoding='utf-8', capture_output=True, check=True)
    return json.loads(value.stdout)


def main():
    subprocess.run(['docker', 'cp', str(ROOT/'scripts/dify/smoke_turn.py'),
                    'docker-api-1:/tmp/dify-recycle-turn.py'], check=True, capture_output=True)
    accounts = dict(base.sql("SELECT account,id FROM user WHERE account IN ('album_test_a','album_test_b')"))
    owner, other = accounts['album_test_a'], accounts['album_test_b']
    check('configured_test_owner', int(base.RUNTIME['AGENT_OWNER_USER_ID']) == owner)
    rows = base.sql("SELECT album_id FROM album WHERE user_id=%s AND album_name='测试旅行' AND type='normal'", (owner,))
    check('unique_test_album', len(rows) == 1)
    album = rows[0][0]
    photos = [row[0] for row in base.sql("SELECT DISTINCT uf.file_id FROM album_picture ap JOIN user_file uf ON uf.file_id=ap.file_id AND uf.user_id=ap.user_id JOIN file f ON f.file_id=uf.file_id WHERE ap.user_id=%s AND ap.album_id=%s AND uf.is_deleted=0 AND f.category='image' ORDER BY uf.file_id", (owner, album))]
    check('bounded_active_test_images', 0 < len(photos) <= 20)
    originals = {file: base.snapshot(owner, file) for file in photos}
    other_before = base.sql('SELECT file_id,is_deleted FROM user_file WHERE user_id=%s ORDER BY file_id', (other,))
    conversation = None
    try:
        first = turn('帮我把相册 测试旅行 里面的图片删掉')
        conversation = first['conversation_id']
        answer = first['answer']
        check('real_recycle_preview_reply', '回收站' in answer and '确认' in answer and '永久删除' not in answer)
        pending = base.sql('SELECT action,status,payload_json FROM agent_pending_action WHERE user_id=%s AND conversation_id=%s ORDER BY id DESC LIMIT 1', (owner, conversation))
        check('server_pending_scope_matches_album_images', len(pending) == 1 and pending[0][0] == 'move_files_to_recycle_bin'
              and pending[0][1] == 'PREVIEWED' and set(json.loads(pending[0][2])['fileIds']) == set(photos))
        check('preview_did_not_mutate_photos', all(base.snapshot(owner, file) == original for file, original in originals.items()))
        second = turn('确认', conversation)
        check('confirmation_produced_execution_reply', '没有可确认' not in second['answer'] and len(second['answer']) > 0)
        result = base.sql('SELECT status,actual_affected_file_count FROM agent_pending_action WHERE user_id=%s AND conversation_id=%s ORDER BY id DESC LIMIT 1', (owner, conversation))
        check('server_execution_succeeded_with_actual_count', result == (('SUCCEEDED', len(photos)),))
        check('all_frozen_photos_in_recycle', all(base.snapshot(owner, file)['relation'] == ((1,),) for file in photos))
        check('album_preserved', base.sql('SELECT album_id FROM album WHERE user_id=%s AND album_id=%s', (owner, album)) == ((album,),))
    finally:
        to_restore = [file for file in photos if base.snapshot(owner, file)['relation'] == ((1,),)]
        if to_restore:
            preview = base.call('previewP3Action', {'action':'restore_files','fileIds':to_restore}, 'restore-dify-album')
            restored = base.call('executeP3Action', base.credentials(preview), 'restore-dify-album')
            check('restore_reported_success', restored.get('success') is True)
        check('original_photo_relations_tags_album_quota_restored', all(base.snapshot(owner, file) == original for file, original in originals.items()))
        check('second_account_unchanged', base.sql('SELECT file_id,is_deleted FROM user_file WHERE user_id=%s ORDER BY file_id', (other,)) == other_before)
        (ROOT/'evaluation/observations/agent-album-recycle-fix-20260915.json').write_text(json.dumps({
            'scope':'local Dify debugger real two-turn workflow + backend + MySQL readback',
            'conversationId':conversation,'photoCount':len(photos),'checks':CHECKS,
            'permanentDeleteTested':False}, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
