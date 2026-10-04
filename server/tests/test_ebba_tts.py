import asyncio
import io
import json
import math
import struct
import wave

import httpx
import pytest

from gateway.ebba_tts import EbbaTextToSpeechEngine


def wav_bytes():
    buffer=io.BytesIO()
    with wave.open(buffer,'wb') as output:
        output.setnchannels(1);output.setsampwidth(2);output.setframerate(16000)
        output.writeframes(b''.join(struct.pack('<h',int(1000*math.sin(i*.1))) for i in range(3200)))
    return buffer.getvalue()


def test_ebba_recipe_and_job_audio_are_converted_for_phone(tmp_path):
    reference=tmp_path/'reference.wav';reference.write_bytes(wav_bytes())
    def response(request):
        if request.url.path=='/v1/tts/jobs':
            payload=json.loads(request.content)
            assert payload['model_backend']=='dots.tts-mf'
            assert payload['seed']==30220701 and payload['dots_num_steps']==16
            assert payload['reference_wav_base64']
            assert 'Serious and matter-of-fact' in payload['voice_instruction']
            return httpx.Response(200,json={'status_url':'/v1/tts/jobs/test'})
        if request.url.path=='/v1/tts/jobs/test':
            return httpx.Response(200,json={'status':'done','audio_url':'/audio/test.wav'})
        assert request.url.path=='/audio/test.wav'
        return httpx.Response(200,content=wav_bytes())
    async def run():
        engine=EbbaTextToSpeechEngine('http://test',reference,transport=httpx.MockTransport(response))
        pcm=b''.join([frame async for frame in engine.synthesize('Dammsugaren är klar.',None,22050)])
        assert len(pcm)==8820 and any(pcm)
    asyncio.run(run())


def test_job_failure_does_not_emit_speech(tmp_path):
    reference=tmp_path/'reference.wav';reference.write_bytes(b'reference')
    def response(request):
        return httpx.Response(200,json={'status_url':'/job'} if request.method=='POST' else {'status':'failed'})
    async def run():
        engine=EbbaTextToSpeechEngine('http://test',reference,transport=httpx.MockTransport(response))
        with pytest.raises(RuntimeError,match='misslyckades'):
            async for _ in engine.synthesize('Test',None,22050):
                pytest.fail('Failed synthesis must not produce audio')
    asyncio.run(run())


def test_job_urls_stay_on_configured_server(tmp_path):
    engine=EbbaTextToSpeechEngine('http://test:8765',tmp_path/'reference')
    with pytest.raises(ValueError,match='origin'):engine.endpoint('http://other/audio')
    with pytest.raises(ValueError,match='origin'):engine.endpoint('//other/audio')
    assert engine.endpoint('/audio')=='http://test:8765/audio'
