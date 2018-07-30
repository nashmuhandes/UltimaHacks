%include "../UltimaPatcher.asm"
%include "include/uw2.asm"

[bits 16]

; This patch removes the view-pitch-dependent vertical scaling of sprites.
;
; UW would scale the height and vertical position of sprites (including those of
; NPCs) up as the 3d perspective pitched up, and down as the 3d perspective
; pitched down. This wasn't a problem originally, as the player was only allowed
; to pitch the view up or down by a small amount. However, as my other patches
; have expanded the allowed range of view pitch, sprites of NPCs would be scaled
; to absurd dimensions when the player pitched the view further up or down.
startPatch EXPANDED_OVERLAY_EXE_LENGTH, \
		do not scale sprite height and position with view pitch
		
	; scale vertical position of sprite
	startBlockAt 0xD197
		; original: imul word [0x14BA]
		
		shr ax, 1
		mov dx, ax
	endBlockAt startAbsolute + 4
	
	; scale height of sprite
	startBlockAt 0xD1B6
		; original: imul word [0x14BA]
		
		shr ax, 1
		mov dx, ax
	endBlockAt startAbsolute + 4
endPatch