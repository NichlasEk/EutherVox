from pathlib import Path
import httpx


class PrinterService:
    """Authenticated client of the shared EutherShould printer cache."""
    def __init__(self, settings, *, transport=None):
        self.enabled = settings.get('enabled') is True
        self.url = settings.get('status_url', 'https://apothictech.se/euthershould/api/printer-service')
        self.users = settings.get('allowed_users', [])
        self.token = Path(settings['token_file']).read_text().strip() if self.enabled else ''
        self.transport = transport
        if self.enabled and (not self.token or self.url != 'https://apothictech.se/euthershould/api/printer-service'):
            raise ValueError('Invalid printer service configuration')

    def can_use(self, user):
        return bool(self.enabled and user and user in self.users)

    async def status(self, user):
        if not self.can_use(user):
            raise ValueError('Printer access denied')
        unavailable = {'available': False, 'label': 'Laserskrivaren', 'model': 'HP Color LaserJet MFP M283fdw'}
        try:
            async with httpx.AsyncClient(timeout=8, trust_env=False, follow_redirects=False, transport=self.transport) as client:
                response = await client.get(self.url, headers={'Authorization': f'Bearer {self.token}', 'X-Printer-User': user})
                response.raise_for_status()
                data = response.json()
            if not isinstance(data, dict):
                return unavailable
            return {key: data[key] for key in ('available', 'label', 'model', 'state', 'alerts', 'updated_at') if key in data}
        except (httpx.HTTPError, ValueError):
            return unavailable

    async def command(self, user, payload):
        if not self.can_use(user): raise ValueError('Åtkomst nekad')
        try:
            async with httpx.AsyncClient(timeout=90, trust_env=False, follow_redirects=False, transport=self.transport) as client:
                response = await client.post(self.url, headers={'Authorization': f'Bearer {self.token}', 'X-Printer-User': user}, json=payload)
                data = response.json()
                if response.status_code != 200: raise ValueError(data.get('error', 'Skrivaråtgärden misslyckades'))
                return data
        except httpx.HTTPError:
            raise ValueError('Kontakten bröts. Kontrollera jobblistan innan du försöker igen.') from None
