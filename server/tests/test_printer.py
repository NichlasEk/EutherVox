import asyncio
import json
import httpx
import pytest
from gateway.printer import PrinterService
from gateway.tools import EutherVoxToolRegistry
from gateway.tool_planner import OllamaToolPlanner
from test_session import make_session, start_message


def service(tmp_path, handler):
    token=tmp_path/'token';token.write_text('synthetic')
    return PrinterService({'enabled':True,'token_file':str(token),'allowed_users':['owner']},transport=httpx.MockTransport(handler))


def test_transport_and_session_authorization(tmp_path):
    def handle(request):
        assert request.headers['Authorization']=='Bearer synthetic'
        assert request.headers['X-Printer-User']=='owner'
        return httpx.Response(200,json={'available':True,'state':'sleeping','label':'Printer','model':'HP','private':'secret'})
    async def scenario():
        printer=service(tmp_path,handle)
        session,sent=make_session();session.printer=printer
        await session.handle_text(start_message())
        from gateway.session import ProtocolError
        with pytest.raises(ProtocolError): await session._printer_status()
        session.authenticated_user='owner'
        await session._printer_status()
        result=sent[-1]
        assert result['type']=='printer.status.result' and result['status']['state']=='sleeping'
        assert 'private' not in result['status']
    asyncio.run(scenario())


@pytest.mark.parametrize('phrase,action', [('Hur mår skrivaren?','status'),('Hur mycket toner finns kvar?','status'),('Skanna ett dokument','scan'),('Skriv ut min PDF','print'),('Visa mina utskriftsjobb','jobs')])
def test_swedish_voice_routing(tmp_path,phrase,action):
    printer=service(tmp_path,lambda _:httpx.Response(200,json={}))
    planner=OllamaToolPlanner(EutherVoxToolRegistry(printer=printer),base_url='http://127.0.0.1:11434',model='mock')
    result=planner.plan_deterministic(phrase,'phone')
    assert result.name=='printer.'+action
