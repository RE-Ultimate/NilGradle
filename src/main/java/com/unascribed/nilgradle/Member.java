package com.unascribed.nilgradle;

import org.cadixdev.bombe.type.signature.MemberSignature;

public class Member {

	public final String owner;
	public final MemberSignature signature;
	
	public Member(String owner, MemberSignature signature) {
		this.owner = owner;
		this.signature = signature;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((owner == null) ? 0 : owner.hashCode());
		result = prime * result + ((signature == null) ? 0 : signature.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Member other = (Member) obj;
		if (owner == null) {
			if (other.owner != null)
				return false;
		} else if (!owner.equals(other.owner))
			return false;
		if (signature == null) {
			if (other.signature != null)
				return false;
		} else if (!signature.equals(other.signature))
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return owner+"."+signature.toJvmsIdentifier();
	}
	
}
