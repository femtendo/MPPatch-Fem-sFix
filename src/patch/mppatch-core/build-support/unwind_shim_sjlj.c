/* Map Itanium unwind names to mingw i686 SjLj (i386 underscore prefix handles the rest) */
void *_Unwind_SjLj_Resume(void *);
void *_Unwind_SjLj_RaiseException(void *);
void *_Unwind_Resume(void *e) { return _Unwind_SjLj_Resume(e); }
void *_Unwind_RaiseException(void *e) { return _Unwind_SjLj_RaiseException(e); }
