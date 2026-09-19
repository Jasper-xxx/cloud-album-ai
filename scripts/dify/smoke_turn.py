import json, sys
from pathlib import Path
from sqlalchemy import select
from sqlalchemy.orm import Session
from app_factory import create_app
from extensions.ext_database import db
from models.model import App
from models.account import Account, Tenant
from models.workflow import Workflow
from services.app_generate_service import AppGenerateService
from core.app.entities.app_invoke_entities import InvokeFrom

args = json.load(sys.stdin)
_, app = create_app()
with app.app_context():
    with Session(db.engine, expire_on_commit=False) as session:
        application = session.get(App, '6d096f09-d847-4269-8e88-3479b8f66559')
        draft = session.scalar(select(Workflow).where(Workflow.app_id == application.id, Workflow.version == 'draft'))
        user = session.get(Account, draft.updated_by or draft.created_by)
        user.current_tenant = session.get(Tenant, application.tenant_id)
        session.expunge_all()
    result = AppGenerateService.generate(app_model=application, user=user,
        args={'inputs':{},'files':[],'auto_generate_name':False, **args}, invoke_from=InvokeFrom.DEBUGGER, streaming=False)
    Path('/tmp/cloud-album-recycle-turn.json').write_text(json.dumps({k:result.get(k) for k in ('answer','conversation_id','message_id')}))
    print(json.dumps({'replyCharacters':len(result.get('answer','')),'conversationId':result.get('conversation_id')}))
