-- Arrow Files cloud copy: who may touch what in the private "arrow-files" bucket.
-- Paste this into Supabase → SQL Editor → New query, then tap Run (once).
--
-- Files live at ArrowFiles/<firebase uid>/..., and the app sends the user's Firebase
-- sign-in token. Supabase checks that token (Authentication → Third-party auth → Firebase),
-- so auth.jwt() ->> 'sub' is the signed-in user's id. Each user only reaches their own folder.

create policy "Arrow Files: read own files"
on storage.objects for select to public
using (bucket_id = 'arrow-files' and (storage.foldername(name))[2] = (auth.jwt() ->> 'sub'));

create policy "Arrow Files: add own files"
on storage.objects for insert to public
with check (bucket_id = 'arrow-files' and (storage.foldername(name))[2] = (auth.jwt() ->> 'sub'));

create policy "Arrow Files: replace own files"
on storage.objects for update to public
using (bucket_id = 'arrow-files' and (storage.foldername(name))[2] = (auth.jwt() ->> 'sub'))
with check (bucket_id = 'arrow-files' and (storage.foldername(name))[2] = (auth.jwt() ->> 'sub'));

create policy "Arrow Files: delete own files"
on storage.objects for delete to public
using (bucket_id = 'arrow-files' and (storage.foldername(name))[2] = (auth.jwt() ->> 'sub'));
